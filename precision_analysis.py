#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RoArm-M2-S Precision Test Analysis / 精度测试数据分析

Usage:
  python precision_analysis.py <test_log.json> <optitrack.csv> [target_poses.json]

Inputs:
  test_log.json       — output from roarm_position_tool_4.py option [21]
  optitrack.csv       — exported from Motive software (see COL_* config below)
  target_poses.json   — mocap ground truth for each target (recorded before experiment)
                        format: {"pos1": {"pos": [x, y, z], "quat": [qx, qy, qz, qw]}, ...}

Outputs (saved next to test_log.json):
  metrics_report.json
  scatter_xy.png, scatter_xz.png
  boxplot_accuracy.png
  block_comparison.png
"""

import sys
import json
import csv
import math
import numpy as np
import matplotlib.pyplot as plt
from pathlib import Path

# ── OptiTrack CSV 列名配置（根据 Motive 导出调整）──────────────────────────
# Motive 默认导出格式示例:
#   Frame, Time, <RigidBodyName> X, <RigidBodyName> Y, <RigidBodyName> Z,
#               <RigidBodyName> Qx, <RigidBodyName> Qy, <RigidBodyName> Qz, <RigidBodyName> Qw
# 把下面的值改成你实际导出文件的列名
COL_TIME = "Time"
COL_X    = "X"
COL_Y    = "Y"
COL_Z    = "Z"
COL_QX   = "Qx"
COL_QY   = "Qy"
COL_QZ   = "Qz"
COL_QW   = "Qw"
MOCAP_FPS = 120          # OptiTrack 采样率（用于估算窗口帧数）
MOTIVE_HEADER_ROWS = 0   # Motive 在真实数据行前有几行非列名注释行（通常 0 或 2-7）
# ──────────────────────────────────────────────────────────────────────────────


def load_test_log(path):
    with open(path, 'r') as f:
        return json.load(f)


def load_optitrack(path):
    """
    加载 OptiTrack CSV。
    Motive 有时在文件头部有几行注释，用 MOTIVE_HEADER_ROWS 跳过。
    返回 list of dict，每个 dict 有 t / x / y / z，可能有 qx/qy/qz/qw。
    """
    rows = []
    with open(path, 'r', newline='') as f:
        for _ in range(MOTIVE_HEADER_ROWS):
            f.readline()
        reader = csv.DictReader(f)
        for row in reader:
            try:
                entry = {
                    "t": float(row[COL_TIME]),
                    "x": float(row[COL_X]),
                    "y": float(row[COL_Y]),
                    "z": float(row[COL_Z]),
                }
                for col, key in [(COL_QX, "qx"), (COL_QY, "qy"), (COL_QZ, "qz"), (COL_QW, "qw")]:
                    if col in row and row[col].strip():
                        entry[key] = float(row[col])
                rows.append(entry)
            except (ValueError, KeyError):
                continue
    return rows


def detect_sync_pulse(mocap_data, sync_info):
    """
    在 mocap 数据中找运动速度最大的时刻，对齐到 sync_pulse.t_sync_start，
    返回时钟偏移 offset = mocap_time - wall_time。
    """
    if len(mocap_data) < 2:
        print("  ⚠️  mocap 数据不足，时钟偏移设为 0")
        return 0.0

    dists = []
    for i in range(1, len(mocap_data)):
        dx = mocap_data[i]["x"] - mocap_data[i-1]["x"]
        dy = mocap_data[i]["y"] - mocap_data[i-1]["y"]
        dz = mocap_data[i]["z"] - mocap_data[i-1]["z"]
        dists.append(math.sqrt(dx*dx + dy*dy + dz*dz))

    max_idx = dists.index(max(dists))
    mocap_sync_t = mocap_data[max_idx + 1]["t"]
    wall_sync_t  = sync_info["t_sync_start"]
    offset = mocap_sync_t - wall_sync_t
    print(f"  Sync offset: {offset:+.3f}s  (mocap={mocap_sync_t:.3f}, wall={wall_sync_t:.3f})")
    return offset


def extract_window(mocap_data, t_start_wall, t_end_wall, offset):
    """
    截取 [t_start_wall, t_end_wall] 对应的 mocap 帧（加时钟偏移），
    返回均值 / 标准差 dict，或 None（若窗口内无数据）。
    """
    t0 = t_start_wall + offset
    t1 = t_end_wall   + offset
    frames = [f for f in mocap_data if t0 <= f["t"] <= t1]
    if not frames:
        return None

    xs = [f["x"] for f in frames]
    ys = [f["y"] for f in frames]
    zs = [f["z"] for f in frames]
    result = {
        "mean": (float(np.mean(xs)), float(np.mean(ys)), float(np.mean(zs))),
        "std":  (float(np.std(xs)),  float(np.std(ys)),  float(np.std(zs))),
        "n": len(frames),
    }
    if "qw" in frames[0]:
        result["q_mean"] = (
            float(np.mean([f["qx"] for f in frames])),
            float(np.mean([f["qy"] for f in frames])),
            float(np.mean([f["qz"] for f in frames])),
            float(np.mean([f["qw"] for f in frames])),
        )
    return result


def quat_angle_deg(q1, q2):
    """两个四元数 (qx, qy, qz, qw) 之间的旋转角度（度）"""
    dot = sum(a * b for a, b in zip(q1, q2))
    dot = min(1.0, abs(dot))
    return math.degrees(2 * math.acos(dot))


def compute_metrics(trials_with_mocap, target_poses):
    """
    计算精度指标，按 target 分组。
    返回 dict: {target_name: {metrics...}}
    """
    try:
        from scipy import stats as scipy_stats
        _has_scipy = True
    except ImportError:
        print("  ⚠️  scipy 未安装，跳过 Block A vs B 统计检验")
        _has_scipy = False

    by_target = {}
    for t in trials_with_mocap:
        by_target.setdefault(t["target_name"], []).append(t)

    metrics = {}
    for target_name, trials in by_target.items():
        valid = [t for t in trials if t.get("mocap_pos")]
        if not valid:
            continue

        positions = [t["mocap_pos"]["mean"] for t in valid]
        px = [p[0] for p in positions]
        py = [p[1] for p in positions]
        pz = [p[2] for p in positions]

        rep_x = 3 * float(np.std(px))
        rep_y = 3 * float(np.std(py))
        rep_z = 3 * float(np.std(pz))

        m = {
            "n": len(positions),
            "mean_pos": (float(np.mean(px)), float(np.mean(py)), float(np.mean(pz))),
            "std_pos":  (float(np.std(px)),  float(np.std(py)),  float(np.std(pz))),
            "RP_3sigma_mm": max(rep_x, rep_y, rep_z) * 1000,
            "RP_x_mm": rep_x * 1000,
            "RP_y_mm": rep_y * 1000,
            "RP_z_mm": rep_z * 1000,
        }

        # Accuracy（需要 ground truth）
        gt = target_poses.get(target_name)
        if gt:
            gt_pos = np.array(gt["pos"])
            errors = [float(np.linalg.norm(np.array(p) - gt_pos)) for p in positions]
            m["AP_mean_mm"] = float(np.mean(errors)) * 1000
            m["AP_max_mm"]  = float(np.max(errors))  * 1000
            m["AP_errors_mm"] = [e * 1000 for e in errors]

            # Orientation accuracy
            if gt.get("quat"):
                q_errors = []
                for t in valid:
                    q = t["mocap_pos"].get("q_mean")
                    if q:
                        q_errors.append(quat_angle_deg(q, gt["quat"]))
                if q_errors:
                    m["orient_accuracy_mean_deg"] = float(np.mean(q_errors))
                    m["orient_accuracy_max_deg"]  = float(np.max(q_errors))
                    m["orient_repeatability_3sigma_deg"] = 3 * float(np.std(q_errors))

        # Block A vs B
        a_pos = [np.array(t["mocap_pos"]["mean"]) for t in valid if t["block"] == "A"]
        b_pos = [np.array(t["mocap_pos"]["mean"]) for t in valid if t["block"] == "B"]
        centroid = np.array(m["mean_pos"])
        if len(a_pos) > 1 and len(b_pos) > 1:
            a_errs = [float(np.linalg.norm(p - centroid)) for p in a_pos]
            b_errs = [float(np.linalg.norm(p - centroid)) for p in b_pos]
            m["block_A_mean_mm"] = float(np.mean(a_errs)) * 1000
            m["block_B_mean_mm"] = float(np.mean(b_errs)) * 1000
            if _has_scipy:
                _, pval = scipy_stats.mannwhitneyu(a_errs, b_errs, alternative='two-sided')
                m["block_A_vs_B_pval"] = float(pval)

        metrics[target_name] = m

    return metrics


def plot_results(trials_with_mocap, target_poses, metrics, out_dir):
    out_dir = Path(out_dir)
    colors = plt.cm.tab10.colors

    by_target = {}
    for t in trials_with_mocap:
        if t.get("mocap_pos"):
            by_target.setdefault(t["target_name"], []).append(t)

    target_names = sorted(by_target.keys())

    def _scatter(axis_pair, fname, xlabel, ylabel, title):
        xi, yi = axis_pair
        fig, ax = plt.subplots(figsize=(8, 8))
        for i, name in enumerate(target_names):
            pts = [t["mocap_pos"]["mean"] for t in by_target[name]]
            ax.scatter([p[xi] for p in pts], [p[yi] for p in pts],
                       label=name, color=colors[i % len(colors)], alpha=0.7, s=30)
            if name in target_poses:
                gt = target_poses[name]["pos"]
                ax.plot(gt[xi], gt[yi], 'x', color=colors[i % len(colors)],
                        markersize=12, markeredgewidth=2)
        ax.set_xlabel(xlabel); ax.set_ylabel(ylabel)
        ax.set_title(title + "  (× = ground truth)")
        ax.legend(); ax.set_aspect('equal')
        plt.tight_layout()
        plt.savefig(out_dir / fname, dpi=150)
        plt.close()
        print(f"  Saved: {fname}")

    _scatter((0, 1), "scatter_xy.png", "X (m)", "Y (m)", "XY plane")
    _scatter((0, 2), "scatter_xz.png", "X (m)", "Z (m)", "XZ plane")

    # boxplot_accuracy.png
    names_with_ap = [n for n, m in metrics.items() if "AP_errors_mm" in m]
    if names_with_ap:
        fig, ax = plt.subplots(figsize=(max(6, len(names_with_ap) * 2), 6))
        ax.boxplot([metrics[n]["AP_errors_mm"] for n in names_with_ap], labels=names_with_ap)
        ax.set_ylabel("Position Error (mm)")
        ax.set_title("Position Accuracy by Target")
        ax.grid(True, axis='y', alpha=0.3)
        plt.tight_layout()
        plt.savefig(out_dir / "boxplot_accuracy.png", dpi=150)
        plt.close()
        print("  Saved: boxplot_accuracy.png")

    # block_comparison.png
    names_with_blocks = [n for n, m in metrics.items() if "block_A_mean_mm" in m]
    if names_with_blocks:
        fig, ax = plt.subplots(figsize=(max(6, len(names_with_blocks) * 2), 6))
        x = np.arange(len(names_with_blocks))
        w = 0.35
        ax.bar(x - w/2, [metrics[n]["block_A_mean_mm"] for n in names_with_blocks], w, label="Block A (fixed start)")
        ax.bar(x + w/2, [metrics[n]["block_B_mean_mm"] for n in names_with_blocks], w, label="Block B (random start)")
        ax.set_xticks(x); ax.set_xticklabels(names_with_blocks)
        ax.set_ylabel("Mean deviation from centroid (mm)")
        ax.set_title("Block A vs B Repeatability Comparison")
        ax.legend(); ax.grid(True, axis='y', alpha=0.3)
        plt.tight_layout()
        plt.savefig(out_dir / "block_comparison.png", dpi=150)
        plt.close()
        print("  Saved: block_comparison.png")


def print_report(metrics):
    print("\n" + "=" * 65)
    print("  PRECISION TEST REPORT / 精度测试报告")
    print("=" * 65)
    for target_name, m in sorted(metrics.items()):
        print(f"\n  📍 {target_name}  (n={m['n']})")
        print(f"     Repeatability (3σ):    {m['RP_3sigma_mm']:.2f} mm")
        print(f"     RP per axis:  X={m['RP_x_mm']:.2f}  Y={m['RP_y_mm']:.2f}  Z={m['RP_z_mm']:.2f} mm")
        if "AP_mean_mm" in m:
            print(f"     Accuracy (mean):       {m['AP_mean_mm']:.2f} mm")
            print(f"     Accuracy (max):        {m['AP_max_mm']:.2f} mm")
        if "orient_accuracy_mean_deg" in m:
            print(f"     Orient accuracy (mean): {m['orient_accuracy_mean_deg']:.2f}°")
            print(f"     Orient repeatab. (3σ):  {m['orient_repeatability_3sigma_deg']:.2f}°")
        if "block_A_vs_B_pval" in m:
            sig = "**" if m["block_A_vs_B_pval"] < 0.05 else "ns"
            print(f"     Block A={m['block_A_mean_mm']:.2f}mm  B={m['block_B_mean_mm']:.2f}mm  "
                  f"p={m['block_A_vs_B_pval']:.3f} {sig}")
    print("=" * 65)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    log_path        = sys.argv[1]
    optitrack_path  = sys.argv[2]
    target_poses_path = sys.argv[3] if len(sys.argv) > 3 else None

    print(f"Loading test log:       {log_path}")
    log = load_test_log(log_path)

    print(f"Loading OptiTrack CSV:  {optitrack_path}")
    mocap_data = load_optitrack(optitrack_path)
    print(f"  {len(mocap_data)} frames loaded")

    target_poses = {}
    if target_poses_path and Path(target_poses_path).exists():
        print(f"Loading target poses:   {target_poses_path}")
        with open(target_poses_path, 'r') as f:
            target_poses = json.load(f)
    else:
        print("  (no target_poses.json → accuracy metrics skipped)")

    print("\nDetecting sync pulse...")
    offset = detect_sync_pulse(mocap_data, log["sync_pulse"])

    print(f"\nProcessing {len(log['trials'])} trials...")
    trials_with_mocap = []
    missing = 0
    for trial in log["trials"]:
        mocap_pos = extract_window(
            mocap_data,
            trial["t_mocap_start"],
            trial["t_mocap_end"],
            offset,
        )
        if not mocap_pos:
            missing += 1
        trials_with_mocap.append({**trial, "mocap_pos": mocap_pos})
    if missing:
        print(f"  ⚠️  {missing} trials had no mocap data in their window")

    print("\nComputing metrics...")
    metrics = compute_metrics(trials_with_mocap, target_poses)

    print_report(metrics)

    out_dir = Path(log_path).parent
    metrics_path = out_dir / "metrics_report.json"
    with open(metrics_path, 'w') as f:
        json.dump(metrics, f, indent=2, default=float)
    print(f"\nMetrics saved: {metrics_path}")

    print("\nGenerating plots...")
    plot_results(trials_with_mocap, target_poses, metrics, out_dir)

    print("\nDone!")


if __name__ == "__main__":
    main()
