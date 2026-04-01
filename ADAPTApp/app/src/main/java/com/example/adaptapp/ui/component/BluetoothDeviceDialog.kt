package com.example.adaptapp.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.adaptapp.connection.BluetoothSppManager
import com.example.adaptapp.ui.theme.*

private enum class ScanState { SCANNING, FOUND, NOT_FOUND, ERROR, BT_OFF }

@Composable
fun BluetoothDeviceDialog(
    btManager: BluetoothSppManager,
    onDeviceSelected: (address: String) -> Unit,
    onDismiss: () -> Unit
) {
    var scanState by remember { mutableStateOf(ScanState.SCANNING) }
    var foundAddress by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val btEnabled = remember { btManager.isBluetoothEnabled() }
    if (!btEnabled) scanState = ScanState.BT_OFF

    fun handleScanResult(name: String, address: String) {
        when {
            name == "[SCAN_DONE]" -> {
                if (scanState == ScanState.SCANNING) scanState = ScanState.NOT_FOUND
            }
            name.startsWith("[ERROR]") -> {
                errorMessage = name.removePrefix("[ERROR] ")
                scanState = ScanState.ERROR
            }
            name == BluetoothSppManager.TARGET_DEVICE_NAME -> {
                foundAddress = address
                scanState = ScanState.FOUND
                btManager.stopDiscovery()
            }
        }
    }

    fun doStartScan() {
        scanState = ScanState.SCANNING
        errorMessage = ""
        btManager.startDiscovery { info -> handleScanResult(info.name, info.address) }
    }

    DisposableEffect(Unit) {
        if (btEnabled) {
            btManager.startDiscovery { info -> handleScanResult(info.name, info.address) }
        }
        onDispose { btManager.stopDiscovery() }
    }

    Dialog(onDismissRequest = { btManager.stopDiscovery(); onDismiss() }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AdaptWhite)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SELECT A DEVICE",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AdaptTextPrimary
                    )
                    IconButton(onClick = { btManager.stopDiscovery(); onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (scanState) {
                    ScanState.BT_OFF -> {
                        Text(
                            "Bluetooth is turned off.\nPlease enable it in system settings.",
                            fontSize = 14.sp,
                            color = AdaptGrayDark,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Close")
                        }
                    }

                    ScanState.ERROR -> {
                        Text(
                            "Scan failed",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptRed
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, fontSize = 14.sp, color = AdaptGrayDark, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { doStartScan() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                        ) { Text("Retry") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }

                    ScanState.SCANNING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = AdaptBlue
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Looking for \"${BluetoothSppManager.TARGET_DEVICE_NAME}\"...",
                            fontSize = 14.sp,
                            color = AdaptGrayDark,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { btManager.stopDiscovery(); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel") }
                    }

                    ScanState.FOUND -> {
                        Text(
                            "ADAPT_ARM Found!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(foundAddress, fontSize = 12.sp, color = AdaptGrayDark)
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { onDeviceSelected(foundAddress) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                        ) {
                            Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }

                    ScanState.NOT_FOUND -> {
                        Text(
                            "Not Found",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AdaptRed
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\"${BluetoothSppManager.TARGET_DEVICE_NAME}\" was not found nearby.\nMake sure the arm is powered on.",
                            fontSize = 14.sp,
                            color = AdaptGrayDark,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { doStartScan() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                        ) { Text("Retry") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
