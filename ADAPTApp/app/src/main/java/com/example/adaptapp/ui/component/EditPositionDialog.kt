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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.ui.theme.*

private val NAME_REGEX = Regex("^[a-zA-Z0-9 _-]+$")

@Composable
fun EditPositionDialog(
    position: ArmPosition,
    onSave: (newName: String, isSafe: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(position.name) }
    var isSafe by remember { mutableStateOf(position.isSafe) }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        error = when {
            name.isBlank() -> "Name cannot be empty"
            !NAME_REGEX.matches(name) -> "Only English letters, numbers, spaces, - and _ allowed"
            else -> null
        }
        return error == null
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AdaptWhite)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Text(
                        text = "Edit Position",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AdaptTextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Name label
                Text(
                    text = "NAME",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AdaptGrayDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        fontSize = 12.sp,
                        color = AdaptRed
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Safe checkbox
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
                Spacer(modifier = Modifier.height(20.dp))

                // Save button
                Button(
                    onClick = { if (validate()) onSave(name.trim(), isSafe) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AdaptBlue)
                ) {
                    Text("SAVE", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AdaptWhite)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Delete button
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete Position", fontSize = 14.sp, color = AdaptRed)
                }
            }
        }
    }
}
