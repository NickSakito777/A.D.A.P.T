package com.example.adaptapp.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.adaptapp.ui.theme.*

@Composable
fun ControlModeDialog(
    onDismiss: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AdaptWhite)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                // 右上角关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AdaptGrayDark,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CONTROL MODE",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AdaptTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap or say\n\"Hey Jarvis, up / down / left / right\"",
                        fontSize = 13.sp,
                        color = AdaptGrayDark,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // 十字形箭头布局
                    ArrowButton(icon = Icons.Default.KeyboardArrowUp, label = "UP", onClick = onUp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArrowButton(icon = Icons.Default.KeyboardArrowLeft, label = "LEFT", onClick = onLeft)
                        ArrowButton(icon = Icons.Default.KeyboardArrowRight, label = "RIGHT", onClick = onRight)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ArrowButton(icon = Icons.Default.KeyboardArrowDown, label = "DOWN", onClick = onDown)
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(88.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = AdaptWhite,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AdaptWhite
            )
        }
    }
}
