package com.utbionic.sesame

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
    onDismissRequest: () -> Unit,
    onConfirmation: (
        newMomNumber: String,
        newPswNumber: String,
    ) -> Unit,
) {
    var newMomNumber by remember { mutableStateOf(currentMomNumber) }
    var newPswNumber by remember { mutableStateOf(currentPswNumber) }

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
        }
    }, onDismissRequest = {
        onDismissRequest()
    }, confirmButton = {
        TextButton(
            onClick = {
                onConfirmation(newMomNumber, newPswNumber)
                onDismissRequest()
            }) {
            Text("Confirm")
        }
    })
}