package com.example.adaptapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.adaptapp.connection.BluetoothSppManager
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.connection.UsbSerialManager
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.EmergencyStopButton
import com.example.adaptapp.ui.screen.DebugScreen
import com.example.adaptapp.ui.screen.HomeScreen
import com.example.adaptapp.ui.screen.PositionsScreen
import com.example.adaptapp.ui.screen.SetupScreen
import com.example.adaptapp.ui.component.BluetoothDeviceDialog
import com.example.adaptapp.ui.theme.ADAPTAppTheme
import com.example.adaptapp.ui.theme.AdaptGrayDark
import com.example.adaptapp.ui.theme.AdaptTextPrimary
import com.example.adaptapp.voice.VoiceCommandHandler
import com.example.adaptapp.voice.VoiceFeedback
import com.example.adaptapp.voice.WakeWordService
import kotlinx.coroutines.launch

enum class Screen { HOME, POSITIONS, SETUP, DEBUG }

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbSerialManager
    private lateinit var btManager: BluetoothSppManager
    private lateinit var armController: ArmController
    private lateinit var positionRepository: PositionRepository
    private lateinit var voiceFeedback: VoiceFeedback
    private lateinit var wakeWordService: WakeWordService
    private lateinit var voiceCommandHandler: VoiceCommandHandler
    private var voiceAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)

        usbManager = UsbSerialManager(this)
        btManager = BluetoothSppManager(this)
        armController = ArmController(usbManager)
        positionRepository = PositionRepository(this)

        voiceFeedback = VoiceFeedback(this)
        wakeWordService = WakeWordService(this)
        voiceAvailable = wakeWordService.initialize()

        voiceCommandHandler = VoiceCommandHandler(
            context = this,
            armController = armController,
            positionRepository = positionRepository,
            feedback = voiceFeedback
        )

        wakeWordService.onWakeWord = { keyword -> voiceCommandHandler.onWakeWord(keyword) }
        voiceCommandHandler.onWakeWordStart = { wakeWordService.start() }
        voiceCommandHandler.onWakeWordStop = { wakeWordService.stop() }

        if (!voiceAvailable) {
            Toast.makeText(this, "Voice control unavailable (model files missing)", Toast.LENGTH_LONG).show()
        }

        setContent {
            ADAPTAppTheme {
                var currentScreen by remember { mutableStateOf(Screen.HOME) }
                var activeMode by remember { mutableStateOf(ConnectionMode.USB) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                var showBtPicker by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                val activeConnection: ConnectionManager = when (activeMode) {
                    ConnectionMode.USB -> usbManager
                    ConnectionMode.BLUETOOTH -> btManager
                }

                LaunchedEffect(activeMode) {
                    armController.connection = activeConnection
                }

                val onSwitchMode: (ConnectionMode) -> Unit = { newMode ->
                    activeMode = newMode
                }

                val onBtTap: () -> Unit = {
                    if (!(activeMode == ConnectionMode.BLUETOOTH && activeConnection.connectionState.value == ConnectionState.CONNECTED)) {
                        val paired = btManager.getPairedDevices()
                        val target = paired.find { it.name == BluetoothSppManager.TARGET_DEVICE_NAME }
                        if (target != null) {
                            activeMode = ConnectionMode.BLUETOOTH
                            btManager.connectToDevice(target.address)
                        } else {
                            showBtPicker = true
                        }
                    }
                }

                val voiceState by voiceCommandHandler.voiceState.collectAsState()
                var voiceEnabled by remember { mutableStateOf(true) }
                LaunchedEffect(currentScreen, voiceEnabled) {
                    if ((currentScreen == Screen.HOME || currentScreen == Screen.POSITIONS) && voiceAvailable && voiceEnabled) {
                        voiceCommandHandler.resume()
                    } else {
                        voiceCommandHandler.pause()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = !armController.isStopped &&
                            (currentScreen == Screen.HOME || currentScreen == Screen.POSITIONS),
                        drawerContent = {
                            DrawerContent(
                                onNavigate = { screen ->
                                    currentScreen = screen
                                    scope.launch { drawerState.close() }
                                },
                                onClose = { scope.launch { drawerState.close() } },
                                onEmergencyStop = { armController.emergencyStop() }
                            )
                        }
                    ) {
                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                connection = activeConnection,
                                controller = armController,
                                repository = positionRepository,
                                currentMode = activeMode,
                                onSwitchMode = onSwitchMode,
                                onBtTap = onBtTap,
                                voiceState = voiceState,
                                voiceEnabled = voiceEnabled,
                                onVoiceToggle = { voiceEnabled = it },
                                onEnterSetup = { currentScreen = Screen.SETUP },
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            Screen.POSITIONS -> PositionsScreen(
                                connection = activeConnection,
                                controller = armController,
                                repository = positionRepository,
                                currentMode = activeMode,
                                onSwitchMode = onSwitchMode,
                                onBtTap = onBtTap,
                                voiceState = voiceState,
                                voiceEnabled = voiceEnabled,
                                onVoiceToggle = { voiceEnabled = it },
                                onEnterSetup = { currentScreen = Screen.SETUP },
                                onOpenDebug = { currentScreen = Screen.DEBUG },
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            Screen.SETUP -> SetupScreen(
                                connection = activeConnection,
                                controller = armController,
                                repository = positionRepository,
                                onExit = { currentScreen = Screen.POSITIONS }
                            )
                            Screen.DEBUG -> DebugScreen(
                                connection = activeConnection,
                                btManager = btManager,
                                currentMode = activeMode,
                                onSwitchMode = onSwitchMode,
                                onBack = { currentScreen = Screen.POSITIONS }
                            )
                        }
                    }

                    if (armController.isStopped) {
                        StopActiveOverlay()
                    }
                }

                if (showBtPicker) {
                    BluetoothDeviceDialog(
                        btManager = btManager,
                        onDeviceSelected = { address ->
                            showBtPicker = false
                            activeMode = ConnectionMode.BLUETOOTH
                            btManager.connectToDevice(address)
                        },
                        onDismiss = { showBtPicker = false }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandHandler.destroy()
        wakeWordService.destroy()
        voiceFeedback.destroy()
        usbManager.disconnect()
        btManager.disconnect()
    }
}

@Composable
private fun DrawerContent(
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    onEmergencyStop: () -> Unit
) {
    ModalDrawerSheet(drawerContainerColor = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close menu")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            DrawerItem("HOME") { onNavigate(Screen.HOME) }
            Spacer(modifier = Modifier.height(16.dp))
            DrawerItem("POSITIONS") { onNavigate(Screen.POSITIONS) }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "ABOUT",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFBDBDBD),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = { onNavigate(Screen.DEBUG) }) {
                Text("Debug Console", fontSize = 12.sp, color = AdaptGrayDark)
            }

            Spacer(modifier = Modifier.height(12.dp))

            EmergencyStopButton(onStop = onEmergencyStop)
        }
    }
}

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = AdaptTextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun StopActiveOverlay() {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Emergency Stop Active",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AdaptTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Motion disabled",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Voice disabled",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Do not touch or move the arm unless instructed.",
                    fontSize = 15.sp,
                    color = AdaptGrayDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Controls stay locked in this build until you restart the app or connect a future recovery flow.",
                    fontSize = 14.sp,
                    color = AdaptGrayDark
                )
            }
        }
    }
}
