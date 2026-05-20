package com.utbionic.verysmartassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun InfoDialog(
    currentMomNumber: String,
    currentPswNumber: String,
    currentControllerAddress: String,
    onDismissRequest: () -> Unit,
    onConfirmation: (
        newMomNumber: String,
        newPswNumber: String,
        newControllerAddress: String,
    ) -> Unit,
) {
    var newMomNumber by remember { mutableStateOf(currentMomNumber) }
    var newPswNumber by remember { mutableStateOf(currentPswNumber) }
    var newControllerAddress by remember { mutableStateOf(currentControllerAddress) }

    AlertDialog(title = {
        Text("Update Information")
    }, text = {
        Column {
            OutlinedTextField(
                value = newMomNumber,
                onValueChange = { newMomNumber = it.filter { c -> c.isDigit() } },
                label = { Text("Mom Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = newPswNumber,
                onValueChange = { newPswNumber = it.filter { c -> c.isDigit() } },
                label = { Text("PSW Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = newControllerAddress,
                onValueChange = { newControllerAddress = it.trim() },
                label = { Text("Controller Address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
        }
    }, onDismissRequest = {
        onDismissRequest()
    }, confirmButton = {
        TextButton(
            onClick = {
                onConfirmation(newMomNumber, newPswNumber, newControllerAddress)
                onDismissRequest()
            }) {
            Text("Confirm")
        }
    })
}