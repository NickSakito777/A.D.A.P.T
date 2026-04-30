package com.example.adaptapp.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.controller.AlignmentStatus
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.controller.AutoLevelController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.model.SessionBaseline
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.EmergencyStopButton
import com.example.adaptapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SetupStep { CONFIRM, DRAG, ALIGN, SAVE }

@Composable
fun SetupScreen(
    connection: ConnectionManager,
    controller: ArmController,
    autoLevelController: AutoLevelController,
    repository: PositionRepository,
    onExit: () -> Unit,
    onEmergencyStop: () -> Unit
) {
    var step by remember { mutableStateOf(SetupStep.CONFIRM) }
    var positionName by remember { mutableStateOf("") }
    var isSafe by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isMoving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var lastResponse by remember { mutableStateOf("") }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(connection) {
        connection.addOnReceiveListener("setup") { message ->
            mainHandler.post { lastResponse = message }
        }
        onDispose {
            connection.removeOnReceiveListener("setup")
        }
    }

    val safePosition = remember { repository.getAll().find { it.isSafe } }
    val torqueClosed = remember {
        ArmPosition("torque closed", b = 0.058, s = -0.060, e = 1.580, t = 3.137)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Top bar: X or <- button + title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step == SetupStep.ALIGN || step == SetupStep.SAVE) {
                IconButton(onClick = {
                    if (step == SetupStep.SAVE) {
                        step = SetupStep.ALIGN
                    } else {
                        // ALIGN → back to DRAG, re-release torque
                        scope.launch {
                            autoLevelController.reset()
                            controller.setTorque(false)
                            step = SetupStep.DRAG
                        }
                    }
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            } else {
                IconButton(onClick = {
                    // Cancel: restore torque and exit
                    controller.setTorque(true)
                    controller.setRollTorque(true)
                    onExit()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text("SET MODE", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Step indicator (3 steps)
        StepIndicator(step)

        Spacer(modifier = Modifier.height(24.dp))

        // Content area
        Box(modifier = Modifier.weight(1f)) {
            when (step) {
                SetupStep.CONFIRM -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (safePosition != null)
                                "Do not touch the arm while it returns to a safe position"
                            else
                                "Do not touch the arm while it returns to a starting position",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            "CONFIRM MOVE",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        if (isMoving) {
                            CircularProgressIndicator(color = AdaptBlue)
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                // Confirm button (blue)
                                Button(
                                    onClick = {
                                        isMoving = true
                                        scope.launch {
                                            if (safePosition != null) {
                                                controller.moveTo(safePosition)
                                            } else {
                                                controller.moveDirectTo(torqueClosed, speed = 600, acc = 20)
                                            }
                                            delay(5000)
                                            controller.setTorque(false)
                                            isMoving = false
                                            step = SetupStep.DRAG
                                        }
                                    },
                                    modifier = Modifier.size(72.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Confirm",
                                        tint = AdaptWhite,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(32.dp))

                                // Cancel button (red)
                                Button(
                                    onClick = {
                                        controller.setTorque(true)
                                        controller.setRollTorque(true)
                                        onExit()
                                    },
                                    modifier = Modifier.size(72.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AdaptRed),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel",
                                        tint = AdaptWhite,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                SetupStep.DRAG -> {
                    var armMoved by remember { mutableStateOf(false) }
                    var initialPos by remember { mutableStateOf<ArmPosition?>(null) }

                    LaunchedEffect(Unit) {
                        controller.readFeedback()
                        delay(600)
                        initialPos = ArmController.parseFeedback(lastResponse)
                        while (!armMoved) {
                            delay(1000)
                            controller.readFeedback()
                            delay(600)
                            val current = ArmController.parseFeedback(lastResponse)
                            val init = initialPos
                            if (current != null && init != null) {
                                val threshold = 0.05
                                if (Math.abs(current.b - init.b) > threshold ||
                                    Math.abs(current.s - init.s) > threshold ||
                                    Math.abs(current.e - init.e) > threshold) {
                                    armMoved = true
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                                .padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "ARM FREE — Drag to position",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            "Move the arm to your desired position, then tap Lock.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    controller.setTorque(false)
                                    controller.setRollTorque(false)
                                    Toast.makeText(context, "Torque released", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "TORQUE OFF",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AdaptTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    controller.setTorque(true)
                                    delay(500)
                                    controller.setRollTorque(false)
                                    delay(300)
                                    step = SetupStep.ALIGN
                                }
                            },
                            enabled = armMoved,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AdaptBlue,
                                disabledContainerColor = Color(0xFFBDBDBD)
                            )
                        ) {
                            Text(
                                "Confirm",
                                fontSize = 20.sp,
                                color = if (armMoved) AdaptWhite else Color(0xFF757575),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                SetupStep.ALIGN -> {
                    val alignStatus by autoLevelController.status.collectAsState()
                    var isLandscape by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Align the phone before saving.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // ALIGN 按钮
                        Button(
                            onClick = { autoLevelController.start() },
                            enabled = alignStatus != AlignmentStatus.ALIGNING,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when (alignStatus) {
                                    AlignmentStatus.ALIGNED -> AdaptGreen
                                    AlignmentStatus.FAILED -> AdaptRed
                                    AlignmentStatus.ALIGNING -> Color(0xFFBDBDBD)
                                    else -> AdaptBlue
                                }
                            )
                        ) {
                            Text(
                                when (alignStatus) {
                                    AlignmentStatus.ALIGNING -> "ALIGNING..."
                                    AlignmentStatus.ALIGNED -> "ALIGNED ✓"
                                    AlignmentStatus.FAILED -> "FAILED — TAP TO RETRY"
                                    else -> "ALIGN"
                                },
                                fontSize = 18.sp,
                                color = AdaptWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Portrait / Landscape 切换
                        Button(
                            onClick = { isLandscape = !isLandscape },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF455A64)
                            )
                        ) {
                            Text(
                                if (isLandscape) "LANDSCAPE" else "PORTRAIT",
                                fontSize = 18.sp,
                                color = AdaptWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // NEXT 按钮 → 进入 SAVE
                        Button(
                            onClick = { step = SetupStep.SAVE },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                        ) {
                            Text(
                                "NEXT",
                                fontSize = 18.sp,
                                color = AdaptWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                SetupStep.SAVE -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "You can now let go of the arm.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            "POSITION NAME:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = positionName,
                            onValueChange = { positionName = it; saveError = null },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        saveError?.let {
                            Text(
                                it,
                                color = AdaptRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Set as safe position?",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AdaptTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(
                                checked = isSafe,
                                onCheckedChange = { isSafe = it },
                                colors = CheckboxDefaults.colors(checkedColor = AdaptBlue)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val name = positionName.trim()
                                if (name.isEmpty()) {
                                    saveError = "Name cannot be empty"
                                    return@Button
                                }
                                if (!name.matches(Regex("^[a-zA-Z0-9 _-]+$"))) {
                                    saveError = "English only (letters, numbers, spaces)"
                                    return@Button
                                }

                                scope.launch {
                                    lastResponse = ""
                                    controller.readFeedback()
                                    delay(1000)

                                    val parsed = ArmController.parseFeedback(lastResponse)
                                    if (parsed != null) {
                                        val baseline = SessionBaseline.rollDeg
                                        // 有 baseline → 存 offset；没有 → p=null（recall 时不动 roll）
                                        val rollOffset = if (baseline != null && parsed.p != null) {
                                            parsed.p!! - baseline
                                        } else null
                                        val position = parsed.copy(name = name, isSafe = isSafe, p = rollOffset)
                                        repository.save(position)
                                        controller.setRollTorque(true)
                                        onExit()
                                    } else {
                                        saveError = "Failed to read position. Please try again."
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                        ) {
                            Text(
                                "SAVE",
                                fontSize = 18.sp,
                                color = AdaptWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Emergency stop — 触发后直接退出页面，避免 coroutine / UI 状态悬挂
        EmergencyStopButton(onStop = {
            onEmergencyStop()
            onExit()
        })
    }
}

@Composable
fun StepIndicator(current: SetupStep) {
    val steps = listOf(
        "Confirm" to SetupStep.CONFIRM,
        "Drag" to SetupStep.DRAG,
        "Align" to SetupStep.ALIGN,
        "Save" to SetupStep.SAVE
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        steps.forEach { (label, step) ->
            val isActive = current == step
            val isPast = current.ordinal > step.ordinal
            val color = when {
                isActive -> AdaptBlue
                isPast -> AdaptGreen
                else -> Color(0xFFBDBDBD)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(color, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isPast) "✓" else "${step.ordinal + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
            }
        }
    }
}
