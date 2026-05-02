[![English](https://img.shields.io/badge/Language-English-2563eb?style=for-the-badge)](README.md) [![中文](https://img.shields.io/badge/语言-中文-9ca3af?style=for-the-badge)](README.zh.md)

# A.D.A.P.T.

**App Driven Assistive Positioning Tool** — a wheelchair-mounted robotic phone manipulator for users with upper-limb impairments.

<p align="center">
  <img src="docs/arm-overview.png" width="640" alt="ADAPT arm in operation"/>
</p>

> A 6-DoF robotic arm that lets caregivers store smartphone positions by physically guiding the arm, then lets the end user recall them hands-free through voice or touch — restoring smartphone independence for individuals with cervical spinal cord injuries.

University College London · MSc Rehabilitation Engineering and Assistive Technologies · April 2026

---

## About

Over **15 million people worldwide** live with a spinal cord injury (SCI), with **4,700 new diagnoses each year** in the United Kingdom. Patients with cervical SCI commonly lose all upper-limb motor function, which makes everyday smartphone interaction — communication, environmental control, social participation — practically impossible without assistance. Individuals with high cervical SCI are *65 % less likely* to own a smartphone than those with a low or absent SCI, despite mobile devices being a primary determinant of quality of life.

Existing phone holders fall into two extremes: rigid £5 mounts that require an able-bodied helper to reposition, or £10 000+ industrial robotic systems. ADAPT closes that gap with a sub-£1 000 wheelchair-mountable robotic arm built on the open-source Waveshare RoArm-M2-S, augmented with a custom 3-axis phone mount and an Android-based teach-and-recall application.

**Research question.** *To what extent can a hands-free wheelchair-mounted robotic arm replace current static smartphone mounts in daily use?*

---

## Key features

- **Set / Recall dual-mode UX** — caregivers physically drag the arm into a target pose (servos held in low-torque mode) and save it under a chosen name; the end user later recalls any saved pose by voice or by tapping a tile on the home screen.
- **Three-layer voice stack running in parallel**
  - Wake-word listener (`openWakeWord`, on-device) for the activation phrase *"Hey Jarvis"*
  - Independent emergency-stop continuous recogniser (`Vosk`, on-device) — never blocked by other tasks, so *"Stop"* always preempts whatever else is happening
  - Command recogniser (Android `SpeechRecognizer`) for movement commands
- **ISO 13482 §6.2.2 Category 2 emergency stop** — controlled stop with retained holding torque, so the arm does not collapse on the user during human-robot proximity. Two redundant trigger paths (UI button + voice listener); both routed through a single handler that cancels timers, in-flight recognition, TTS, and the wake-word service before issuing `T:0`.
- **TTS confirmation before motion** — every recognised command requires explicit *"Yes"* before the arm moves, shifting the cost of misrecognition from an unintended motion to a repeated phrase.
- **Unified T-code JSON protocol** — the same firmware serves USB serial (development) and Bluetooth SPP (deployment) through a single message handler. Voice commands, touch gestures, and external scripts all emit the same wire format.
- **6-DoF kinematics** — 3 positional DoF (base, shoulder, elbow) plus 3 attitudinal DoF (roll, pitch, yaw) at the phone mount. The original RoArm wrist pitch was removed to avoid redundancy with the added attitudinal chain, and the shoulder support was reinforced for the increased moment.
- **Joint-space safety limits** in firmware — per-servo angular bounds (e.g. ID15 gripper safe range 55 °–223 °, phone-tilt 284 °–360 °/0 °–106 ° to avoid mechanical collision) enforced before any T-code is applied.
- **Local-first position store** — named poses are serialised as JSON via Gson and written to Android `SharedPreferences`. No database, no network, no cloud telemetry.

---

## Hardware

<p align="center">
  <img src="docs/cad-exploded.png" width="280" alt="Exploded CAD view of the arm"/>
  <img src="docs/phone-mount.png" width="380" alt="3-axis phone mount close-up"/>
</p>

| Component | Spec |
|---|---|
| Base manipulator | Waveshare RoArm-M2-S (ESP32 controller + ST3215 servos) |
| Added DoF | 3 × ST3215 in custom 3-axis (roll, pitch, yaw) phone mount |
| Phone | Redmi Note 9T (representative; any Android ≥ 8.0) |
| Structural | Aluminium-alloy column, PLA 3D-printed joints, copper elbow link |
| Mounting | Steel desktop clamp (RoArm native) |
| Power | 12 V DC · 4.9 W avg · 8 W peak |

The original RoArm 4-DoF chain (base, shoulder, elbow, wrist pitch) was reworked: the wrist pitch was removed to leave a 3-DoF reach chain, and a custom 3-axis phone mount was added at the end-effector to provide the orientation DoF. The shoulder support was reinforced to handle the additional torque introduced by the extended end-effector. A shorter copper elbow-to-end-effector link replaced the original to reduce the moment arm, lowering the upstream power draw.

<p align="center">
  <img src="docs/arm-side.png" width="500" alt="Arm in extended pose"/>
</p>

---

## Software

The software stack consists of an Android application (Kotlin + Jetpack Compose, `minSdk 26`) and an ESP32 firmware (Arduino + SCServo library), connected through a unified application-layer protocol that runs identically over USB serial and Bluetooth SPP.

<p align="center">
  <img src="docs/app-home.png" width="280" alt="ADAPT HOME screen"/>
</p>

### Voice command map

| Command | Recognition layer | Action | T-code |
|---|---|---|---|
| Hey Jarvis | Wake-word (openWakeWord) | Activate arm | – |
| Stop | E-stop (Vosk, continuous) | Halt all motion immediately | `T:0` |
| Resume Control | E-stop (Vosk, continuous) | Clear E-stop, resume control | `T:999` |
| Move to Position *X* | Command (Android SR) | Recall preset | `T:102` |
| Adjust left / right / up / down | Command (Android SR) | Incremental adjust | `T:102` |
| Landscape / Portrait | Command (Android SR) | Switch phone-mount orientation | `T:701` |
| Align | Command (Android SR) | Auto-level phone | `T:100` / `T:700` |

### Repository layout

```
ADAPTApp/                         Android application (Kotlin + Compose)
└── app/src/main/java/com/example/adaptapp/
    ├── connection/               USB serial + Bluetooth SPP managers
    ├── controller/               ArmController, AutoLevel, StepAdjustment
    ├── voice/                    Wake-word, Vosk e-stop, command handler
    ├── kinematics/               Inverse kinematics solver
    ├── repository/               PositionRepository (Gson + SharedPreferences)
    └── ui/screen/                Home, Positions, Setup, Debug

RoArm-M2_example/                 ESP32 firmware (Arduino)
├── RoArm-M2_example.ino          Main entry
├── json_cmd.h                    T-code dispatch table
├── RoArm-M2_module.h             IK, kinematic limits, e-stop sequence
├── RoArm-M2_config.h             Joint limits, servo IDs, IK constants
├── uart_ctrl.h                   T-code parser + emergency-stop gate
├── servo_id_changer/             ID15 gripper limit-test sketch
└── Servoinitial/                 Dual-tilt servo sync test

roarm_position_tool_4.py          Python position-tuning utility
```

---

## Build & run

### Firmware (Arduino)

1. Install Arduino IDE 2.x with the ESP32 board package.
2. Install the `SCServo` library that ships with the RoArm-M2-S.
3. Open `RoArm-M2_example/RoArm-M2_example.ino`.
4. Select board **ESP32 Dev Module**, upload to the RoArm controller via USB-C.

### Android app

1. Open the `ADAPTApp/` directory in Android Studio (Hedgehog or newer).
2. Build the `app` module — Gradle resolves the Vosk and ONNX Runtime dependencies. The `aaptOptions { noCompress("onnx") }` flag is already set so the wake-word ONNX model loads at runtime.
3. Install on a phone with mic + Bluetooth (target Redmi Note 9T or any Android ≥ 8.0). On first launch the user must download the English offline speech package via system Settings → Languages & input → Voice typing → Offline languages, otherwise the command-recogniser layer falls back to network mode.
4. Pair the phone with the RoArm-M2-S Bluetooth module *or* connect via USB-C OTG. The protocol is identical across both transports.

---

## Validation highlights

| Method | Result |
|---|---|
| Finite Element Analysis (ANSYS Mechanical 2020 R2 · full extension · 300 g end-effector load · SOLID185 · 81 046 nodes) | Max deformation **1.054 mm** · max von-Mises stress **22.53 MPa** at the joint · safety factor **2.66** |
| Motion-capture repeatability (OptiTrack · 17 cameras · 120 Hz · 5 targets × 20 trials, ISO 9283:1998) | Mean RP **3.20 mm** (SD 0.92 mm) · mean RO **1.28°** |
| Power consumption (TP-Link Tapo P110 · 4.7 h continuous session) | Avg **4.9 W** · peak **8 W** · ≈ **10 h** projected on a 50 Wh battery |
| Mixed-methods usability (5 participants — 2 healthy, 2 SCI, 1 caregiver · 90 min/session · 3 cycles) | Recall task completion times decreased monotonically across cycles; perceived usefulness scaled with severity of upper-limb impairment |

---

## Limitations & future work

- **Single physical e-stop.** The current emergency stop relies on voice + an on-screen button; a dedicated hardware button on the arm itself would close the safety loop, particularly for users with severe dysarthria or in conditions where the speech recogniser is unreliable.
- **Workspace not constrained.** Path interpolation between presets is not currently bounded; future iterations should add a software workspace envelope to keep the arm clear of the wheelchair joystick and transfer zone (Participant P3 reported the arm "just missed" the joystick during a recall trajectory).
- **Single-modality voice fallback.** Voice control degrades in very quiet or very noisy environments; a joystick or head-tracking secondary modality is planned.
- **Battery integration.** The device was tested mains-powered. Power data indicates a 50 Wh portable pack would support ≈ 8 h of typical use; physical integration onto the wheelchair is the next prototype step.
- **Caregiver-side control app.** Setup currently requires the caregiver to handle the user's phone. A separate caregiver-side application is recommended for clinical deployment.
- **Larger usability cohort.** Only 2 SCI participants were recruited; broader recruitment is needed before generalising the usability findings.

---

## Acknowledgements

- **Dr Tom Carlson** (project supervisor) — UCL Aspire CREATe
- **Ms Ivy Mumuni** — guidance on participant recruitment and hardware development
- **Ms Alison Barnes** — clinical feedback and end-user recruitment
- **The Institute of Making** — 3D-printing and prototyping support
- All PPIE participants and case-study volunteers, whose feedback shaped every design iteration

---

## License

Source code in this repository is released under the [MIT License](LICENSE). The base RoArm-M2-S firmware on which this project builds is © Waveshare and used in accordance with its open-source terms.
