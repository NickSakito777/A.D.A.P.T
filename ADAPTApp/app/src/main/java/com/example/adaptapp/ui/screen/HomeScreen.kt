package com.example.adaptapp.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.ConfirmMoveDialog
import com.example.adaptapp.ui.component.EmergencyStopButton
import com.example.adaptapp.ui.theme.*
import com.example.adaptapp.voice.VoiceState
import com.example.adaptapp.voice.VoiceStatus

private val SafePurpleLight = Color(0xFFE1BEE7)
private val SafePurple = Color(0xFF7B1FA2)

@Composable
fun HomeScreen(
    connection: ConnectionManager,
    controller: ArmController,
    repository: PositionRepository,
    currentMode: ConnectionMode,
    onSwitchMode: ((ConnectionMode) -> Unit)?,
    onBtTap: () -> Unit,
    voiceState: VoiceState?,
    voiceEnabled: Boolean,
    onVoiceToggle: ((Boolean) -> Unit)?,
    onEnterSetup: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by connection.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED

    var confirmTarget by remember { mutableStateOf<ArmPosition?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val allPositions = remember(refreshKey) { repository.getAll() }
    val currentName = remember(refreshKey) { repository.getCurrentPosition() }
    val currentPosition = remember(currentName, allPositions) {
        currentName?.let { name -> allPositions.find { it.name == name } }
    }
    val recents = remember(refreshKey) {
        repository.getRecents().filter { it != currentName }.take(5)
    }
    val safePosition = remember(allPositions) { allPositions.find { it.isSafe } }

    val canTap = isConnected && voiceState?.status != VoiceStatus.EXECUTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AdaptWhite)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 1. TopBar
        HomeTopBar(
            voiceEnabled = voiceEnabled,
            onVoiceToggle = onVoiceToggle,
            btConnected = currentMode == ConnectionMode.BLUETOOTH && isConnected,
            onBtTap = onBtTap,
            onOpenDrawer = onOpenDrawer
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. ConnectionBar
        HomeConnectionBar(
            state = connectionState,
            mode = currentMode,
            onTap = {
                if (connectionState == ConnectionState.CONNECTED) connection.disconnect()
                else connection.connect()
            },
            onSwitchMode = onSwitchMode,
            currentMode = currentMode
        )

        // 3. VoiceStatusBar
        if (voiceEnabled && voiceState != null && voiceState.status != VoiceStatus.PAUSED) {
            Spacer(modifier = Modifier.height(6.dp))
            HomeVoiceStatusBar(voiceState)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 4. CURRENT
            Text("CURRENT", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AdaptGrayDark)
            Spacer(modifier = Modifier.height(8.dp))

            if (currentPosition != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clickable(enabled = canTap) { confirmTarget = currentPosition },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AdaptGreenLight)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            currentPosition.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (currentPosition.isSafe) "Safe position"
                            else "Base %.1f° · Tilt %.1f°".format(
                                Math.toDegrees(currentPosition.b),
                                currentPosition.tilt ?: 0.0
                            ),
                            fontSize = 13.sp,
                            color = AdaptGrayDark
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AdaptGray)
                ) {
                    Text(
                        "No position selected",
                        fontSize = 16.sp,
                        color = AdaptGrayDark,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. RECENTS
            Text("RECENTS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AdaptGrayDark)
            Spacer(modifier = Modifier.height(8.dp))

            if (recents.isEmpty()) {
                Text("No recent positions", fontSize = 14.sp, color = AdaptGrayDark)
            } else {
                recents.forEach { name ->
                    val pos = allPositions.find { it.name == name }
                    if (pos != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable(enabled = canTap) { confirmTarget = pos },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = AdaptGray)
                        ) {
                            Text(
                                pos.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = AdaptTextPrimary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. Safe Position
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (safePosition != null) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clickable(enabled = canTap) { confirmTarget = safePosition },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SafePurpleLight)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("SAFE POSITION", fontSize = 12.sp, color = SafePurple)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                safePosition.name,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = SafePurple
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SafePurpleLight)
                    ) {
                        Text(
                            "No safe position set",
                            fontSize = 14.sp,
                            color = SafePurple,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = {
                        if (isConnected) onEnterSetup()
                        else Toast.makeText(context, "Please connect to the arm first", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SafePurpleLight)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Setup", tint = SafePurple)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 7. Emergency Stop
        EmergencyStopButton(
            onStop = { controller.emergencyStop(repository.getAll().find { it.isSafe }) },
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    // ConfirmMoveDialog
    confirmTarget?.let { target ->
        ConfirmMoveDialog(
            positionName = target.name,
            onConfirm = {
                if (repository.getAll().none { it.isSafe }) {
                    Toast.makeText(context, "Please define a safe position first", Toast.LENGTH_SHORT).show()
                } else {
                    controller.moveTo(target)
                    repository.recordUsage(target.name)
                    refreshKey++
                }
                confirmTarget = null
            },
            onDismiss = { confirmTarget = null }
        )
    }
}

@Composable
private fun HomeTopBar(
    voiceEnabled: Boolean,
    onVoiceToggle: ((Boolean) -> Unit)?,
    btConnected: Boolean,
    onBtTap: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = AdaptTextPrimary)
        }
        Text(
            "HOME",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AdaptTextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (onVoiceToggle != null) {
            IconButton(onClick = { onVoiceToggle(!voiceEnabled) }) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Voice",
                    tint = if (voiceEnabled) AdaptBlue else AdaptGrayDark
                )
            }
        }
        IconButton(onClick = onBtTap) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = "Bluetooth",
                tint = if (btConnected) AdaptBlue else AdaptGrayDark
            )
        }
    }
}

@Composable
private fun HomeConnectionBar(
    state: ConnectionState,
    mode: ConnectionMode,
    onTap: () -> Unit,
    onSwitchMode: ((ConnectionMode) -> Unit)?,
    currentMode: ConnectionMode
) {
    val dotColor = when (state) {
        ConnectionState.CONNECTED -> AdaptGreen
        ConnectionState.CONNECTING -> AdaptBlue
        ConnectionState.DISCONNECTED -> AdaptRed
    }
    val modeLabel = if (mode == ConnectionMode.USB) "USB" else "Bluetooth"
    val stateLabel = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.DISCONNECTED -> "Disconnected"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AdaptGray)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, RoundedCornerShape(5.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("$modeLabel \u00B7 $stateLabel", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AdaptTextPrimary)
        Spacer(modifier = Modifier.weight(1f))
        if (state == ConnectionState.DISCONNECTED && onSwitchMode != null) {
            val altMode = if (currentMode == ConnectionMode.USB) ConnectionMode.BLUETOOTH else ConnectionMode.USB
            val altLabel = if (altMode == ConnectionMode.USB) "Switch to USB" else "Switch to BT"
            TextButton(
                onClick = { onSwitchMode(altMode) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(altLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AdaptBlue)
            }
        }
    }
}

@Composable
private fun HomeVoiceStatusBar(state: VoiceState) {
    val dotColor = when (state.status) {
        VoiceStatus.IDLE -> AdaptGrayDark
        VoiceStatus.LISTENING -> AdaptGreen
        VoiceStatus.CONFIRMING, VoiceStatus.DISAMBIGUATING -> AdaptBlue
        VoiceStatus.EXECUTING -> AdaptBlueDark
        VoiceStatus.PAUSED -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(state.displayText, fontSize = 12.sp, color = dotColor)
    }
}
