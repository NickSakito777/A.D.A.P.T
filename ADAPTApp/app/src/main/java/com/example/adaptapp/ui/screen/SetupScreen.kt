package com.example.adaptapp.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.EmergencyStopButton
import com.example.adaptapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SetupStep {
    FOLDING,
    DRAG_ARM,
    LOCKING,
    ADJUST_ROLL,
    SAVE,
    DONE
}

@Composable
fun SetupScreen(
    connection: ConnectionManager,
    controller: ArmController,
    repository: PositionRepository,
    onExit: () -> Unit
) {
    var step by remember { mutableStateOf(SetupStep.FOLDING) }
    var statusText by remember { mutableStateOf("") }
    var positionName by remember { mutableStateOf("") }
    var isSafe by remember { mutableStateOf(false) }
    var feedbackPosition by remember { mutableStateOf<ArmPosition?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var lastResponse by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        connection.setOnReceiveCallback { message ->
            lastResponse = message
        }
    }

    LaunchedEffect(Unit) {
        statusText = "Moving to fold position..."
        // 先移到 torque closed（T:120 直接模式）
        controller.moveDirectTo(
            ArmPosition("torque closed", b = 0.058, s = -0.060, e = 1.580, t = 3.137),
            speed = 600, acc = 20
        )
        // 等待机械臂到位
        delay(5000)
        // 松扭矩
        statusText = "Releasing torque..."
        controller.setTorque(false)
        delay(500)
        step = SetupStep.DRAG_ARM
        statusText = ""
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text("Setup Mode", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Position Setup", fontSize = 14.sp, color = AdaptGrayDark)
            Spacer(modifier = Modifier.height(12.dp))

            StepIndicator(step)
            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (step) {
                    SetupStep.FOLDING -> {
                        StepContent(
                            title = "Folding Arm...",
                            description = "Do not touch the arm while it returns to a safe position.",
                            showProgress = true
                        )
                    }

                    SetupStep.DRAG_ARM -> {
                        Column {
                            StepContent(
                                title = "Drag Arm to Position",
                                description = "Hold the arm in your desired position, then press confirm."
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    step = SetupStep.LOCKING
                                    scope.launch {
                                        statusText = "Locking arm..."
                                        controller.setTorque(true)
                                        delay(500)
                                        controller.setRollTorque(false)
                                        delay(300)
                                        step = SetupStep.ADJUST_ROLL
                                        statusText = ""
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AdaptGreen
                                )
                            ) {
                                Text(
                                    "POSITION READY",
                                    fontSize = 18.sp,
                                    color = AdaptWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    SetupStep.LOCKING -> {
                        StepContent(
                            title = "Locking Arm...",
                            description = "Locking arm...",
                            showProgress = true
                        )
                    }

                    SetupStep.ADJUST_ROLL -> {
                        Column {
                            StepContent(
                                title = "Adjust Phone Roll",
                                description = "Adjust the phone to your desired orientation, then press confirm."
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { step = SetupStep.SAVE },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AdaptBlueDark
                                )
                            ) {
                                Text(
                                    "CONFIRM",
                                    fontSize = 18.sp,
                                    color = AdaptWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    SetupStep.SAVE -> {
                        Column {
                            StepContent(
                                title = "Save Position",
                                description = "You can now let go of the arm."
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = positionName,
                                onValueChange = { positionName = it; saveError = null },
                                label = { Text("POSITION NAME") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            saveError?.let {
                                Text(it, color = AdaptRed, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Set as safe position?",
                                    fontSize = 14.sp,
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
                                        statusText = "Reading position..."
                                        lastResponse = ""
                                        controller.readFeedback()
                                        delay(1000)

                                        val parsed = ArmController.parseFeedback(lastResponse)
                                        if (parsed != null) {
                                            val position = parsed.copy(name = name, isSafe = isSafe)
                                            repository.save(position)
                                            feedbackPosition = position
                                            controller.setRollTorque(true)
                                            step = SetupStep.DONE
                                            statusText = ""
                                        } else {
                                            saveError = "Failed to read position. Please try again."
                                            statusText = ""
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AdaptBlue
                                )
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

                    SetupStep.DONE -> {
                        Column {
                            StepContent(
                                title = "Position Saved!",
                                description = "Position saved successfully!"
                            )

                            feedbackPosition?.let { pos ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = AdaptGray)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        AngleRow("Base", "%.4f rad".format(pos.b))
                                        AngleRow("Shoulder", "%.4f rad".format(pos.s))
                                        AngleRow("Elbow", "%.4f rad".format(pos.e))
                                        AngleRow("Hand", "%.4f rad".format(pos.t))
                                        pos.p?.let { AngleRow("Roll", "%.2f°".format(it)) }
                                        pos.tilt?.let { AngleRow("Tilt", "%.2f°".format(it)) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = {
                                        step = SetupStep.FOLDING
                                        positionName = ""
                                        isSafe = false
                                        feedbackPosition = null
                                        saveError = null
                                        scope.launch {
                                            statusText = "Moving to fold position..."
                                            controller.moveDirectTo(
                                                ArmPosition("torque closed",
                                                    b = 0.058, s = -0.060, e = 1.580, t = 3.137),
                                                speed = 600, acc = 20
                                            )
                                            delay(5000)
                                            statusText = "Releasing torque..."
                                            controller.setTorque(false)
                                            delay(500)
                                            step = SetupStep.DRAG_ARM
                                            statusText = ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp)
                                ) {
                                    Text("SAVE ANOTHER", fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onExit,
                                    modifier = Modifier.weight(1f).height(56.dp)
                                ) {
                                    Text("DONE", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (statusText.isNotEmpty()) {
                Text(
                    statusText,
                    fontSize = 13.sp,
                    color = AdaptGrayDark,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        controller.setTorque(true)
                        controller.setRollTorque(true)
                        onExit()
                    },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text("CANCEL")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            EmergencyStopButton(onStop = { controller.emergencyStop() })
        }
    }
}

@Composable
fun StepIndicator(current: SetupStep) {
    val steps = listOf(
        "Fold" to SetupStep.FOLDING,
        "Drag" to SetupStep.DRAG_ARM,
        "Lock" to SetupStep.LOCKING,
        "Roll" to SetupStep.ADJUST_ROLL,
        "Save" to SetupStep.SAVE,
        "Done" to SetupStep.DONE
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
                Text(label, fontSize = 10.sp, color = color)
            }
        }
    }
}

@Composable
fun StepContent(
    title: String,
    description: String,
    showProgress: Boolean = false
) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, fontSize = 14.sp, color = AdaptGrayDark, lineHeight = 20.sp)
        if (showProgress) {
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AngleRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = AdaptGrayDark)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
