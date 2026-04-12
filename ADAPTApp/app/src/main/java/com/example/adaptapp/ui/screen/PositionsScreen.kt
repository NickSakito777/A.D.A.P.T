package com.example.adaptapp.ui.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.ConfirmMoveDialog
import com.example.adaptapp.ui.component.EditPositionDialog
import com.example.adaptapp.ui.component.EmergencyStopButton
import com.example.adaptapp.ui.theme.*
import com.example.adaptapp.voice.VoiceState
import com.example.adaptapp.voice.VoiceStatus
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

@Composable
fun PositionsScreen(
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
    onOpenDebug: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val connectionState by connection.connectionState.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val context = LocalContext.current

    var positions by remember { mutableStateOf(repository.getAll()) }
    var currentPage by remember { mutableStateOf(0) }
    var confirmTarget by remember { mutableStateOf<ArmPosition?>(null) }
    var editTarget by remember { mutableStateOf<ArmPosition?>(null) }

    val pageSize = 4
    val totalPages = maxOf(1, (positions.size + pageSize - 1) / pageSize)

    fun refreshPositions() {
        positions = repository.getAll()
        val newTotal = maxOf(1, (positions.size + pageSize - 1) / pageSize)
        if (currentPage >= newTotal) currentPage = maxOf(0, newTotal - 1)
    }

    val pagePositions = positions.drop(currentPage * pageSize).take(pageSize)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AdaptWhite)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 1. Top Bar
        TopBar(
            voiceEnabled = voiceEnabled,
            onVoiceToggle = onVoiceToggle,
            btConnected = currentMode == ConnectionMode.BLUETOOTH && isConnected,
            onBtTap = onBtTap,
            onOpenDrawer = onOpenDrawer
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Connection Bar
        ConnectionBar(
            state = connectionState,
            mode = currentMode,
            onTap = {
                if (connectionState == ConnectionState.CONNECTED) connection.disconnect()
                else connection.connect()
            },
            onSwitchMode = onSwitchMode,
            currentMode = currentMode
        )

        // 3. Voice Status
        if (voiceEnabled && voiceState != null && voiceState.status != VoiceStatus.PAUSED) {
            Spacer(modifier = Modifier.height(6.dp))
            VoiceStatusBar(voiceState)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Pagination
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "<",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (currentPage > 0) AdaptTextPrimary else AdaptGray,
                modifier = Modifier
                    .clickable(enabled = currentPage > 0) { currentPage-- }
                    .padding(horizontal = 16.dp)
            )
            Text(
                "${currentPage + 1} / $totalPages",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AdaptGrayDark
            )
            Text(
                text = ">",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (currentPage < totalPages - 1) AdaptTextPrimary else AdaptGray,
                modifier = Modifier
                    .clickable(enabled = currentPage < totalPages - 1) { currentPage++ }
                    .padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. Position Cards — 4 cards fill available space
        Column(modifier = Modifier.weight(1f)) {
            if (positions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No saved positions", color = AdaptGrayDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                pagePositions.forEach { pos ->
                    PositionCard(
                        position = pos,
                        onTap = {
                            if (isConnected && !controller.isStopped && voiceState?.status != VoiceStatus.EXECUTING)
                                confirmTarget = pos
                        },
                        onEdit = { editTarget = pos },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // 5.5 Safe Position
        val safePosition = positions.find { it.isSafe }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clickable(enabled = isConnected && !controller.isStopped && voiceState?.status != VoiceStatus.EXECUTING && safePosition != null) {
                        safePosition?.let { confirmTarget = it }
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE1BEE7))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        safePosition?.name ?: "No safe position",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7B1FA2),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (!isConnected) Toast.makeText(context, "Please connect to the arm first", Toast.LENGTH_SHORT).show()
                    else if (controller.isStopped) Toast.makeText(context, "Emergency stop active", Toast.LENGTH_SHORT).show()
                    else onEnterSetup()
                },
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE1BEE7))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF7B1FA2))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Add button
        OutlinedButton(
            onClick = onEnterSetup,
            enabled = isConnected && !controller.isStopped,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("ADD ANOTHER POSITION", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 7. Emergency Stop
        EmergencyStopButton(
            onStop = { controller.emergencyStop() },
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    // Dialogs
    confirmTarget?.let { target ->
        ConfirmMoveDialog(
            positionName = target.name,
            onConfirm = {
                if (controller.isStopped) {
                    confirmTarget = null
                    return@ConfirmMoveDialog
                }
                if (positions.none { it.isSafe }) {
                    Toast.makeText(context, "Please define a safe position first", Toast.LENGTH_SHORT).show()
                } else {
                    controller.moveTo(target)
                    repository.recordUsage(target.name)
                }
                confirmTarget = null
            },
            onDismiss = { confirmTarget = null }
        )
    }

    editTarget?.let { target ->
        EditPositionDialog(
            position = target,
            onSave = { newName, isSafe ->
                if (newName != target.name) {
                    repository.delete(target.name)
                }
                repository.save(target.copy(name = newName, isSafe = isSafe))
                refreshPositions()
                editTarget = null
            },
            onDelete = {
                repository.delete(target.name)
                refreshPositions()
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

}

@Composable
private fun TopBar(
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
            "POSITIONS",
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
private fun ConnectionBar(
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
private fun VoiceStatusBar(state: VoiceState) {
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
        Text(state.displayText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = dotColor)
    }
}

@Composable
private fun PositionCard(
    position: ArmPosition,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (position.isSafe) Color(0xFFE1BEE7) else AdaptGray
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                position.name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (position.isSafe) Color(0xFF7B1FA2) else AdaptTextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit",
                    tint = if (position.isSafe) Color(0xFF7B1FA2) else AdaptGrayDark)
            }
        }
    }
}
