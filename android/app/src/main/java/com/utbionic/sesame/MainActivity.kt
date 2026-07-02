package com.utbionic.sesame

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.utbionic.sesame.ui.theme.SesameTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()

    private val scope = MainScope()
    private lateinit var deviceManager: DeviceManager
    private var isControllerConnected by mutableStateOf(false)

    private var pendingCallNumber: String? = null
    private val requestCallPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val number = pendingCallNumber
            pendingCallNumber = null
            if (granted && number != null) {
                placeCall(number)
            } else if (!granted) {
                showMessage("Call permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deviceManager = DeviceManager(applicationContext, scope)
        connect()

        setContent {
            SesameTheme {
                Home(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp),
                    information = information,
                    isControllerConnected = isControllerConnected,
                    onReconnect = { connect() },
                    onCallMom = { call(information.momNumber) },
                    onCallPSW = { call(information.pswNumber) },
                    onOpenApartmentDoor = { openDoor("OPEN_APARTMENT", "Apartment Door") },
                    onOpenRoomDoor = { openDoor("OPEN_ROOM", "Room Door") },
                    onInformationUpdated = { showMessage("Information updated") },
                )
            }
        }
    }

    override fun onDestroy() {
        deviceManager.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        showMessage("Connecting to controller...")
        deviceManager.start { connected ->
            val changed = connected != isControllerConnected
            isControllerConnected = connected
            if (changed) {
                showMessage(if (connected) "Controller connected" else "Controller disconnected")
            }
        }
    }

    private fun call(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            placeCall(phoneNumber)
        } else {
            pendingCallNumber = phoneNumber
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun placeCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = "tel:$phoneNumber".toUri()
        }
        startActivity(intent)
    }

    private fun openDoor(command: String, label: String) {
        showMessage("Opening $label...")
        deviceManager.sendCommand(command) { success ->
            showMessage(if (success) "$label opened" else "Could not open $label")
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Home(
    modifier: Modifier = Modifier,
    information: Information,
    isControllerConnected: Boolean,
    onReconnect: () -> Unit,
    onCallMom: () -> Unit,
    onCallPSW: () -> Unit,
    onOpenApartmentDoor: () -> Unit,
    onOpenRoomDoor: () -> Unit,
    onInformationUpdated: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.verticalScroll(scrollState)) {
        Text("Sesame", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Setup", fontWeight = FontWeight.Bold)
        Text("Mom Phone Number: ${information.momNumber}")
        Text("PSW Phone Number: ${information.pswNumber}")
        Text(
            text = if (isControllerConnected) {
                "Controller Status: Connected"
            } else {
                "Controller Status: Disconnected"
            },
            color = if (isControllerConnected) {
                Color(0xFF2E7D32)
            } else {
                Color(0xFFC62828)
            },
            fontWeight = FontWeight.SemiBold,
        )

        Button(
            onClick = onReconnect, modifier = Modifier.fillMaxWidth()
        ) { Text("Reconnect Controller") }
        Text(
            "Reconnects to the door controller", fontSize = 12.sp, color = Color.Gray
        )

        Button(
            onClick = { showInfoDialog = true }, modifier = Modifier.fillMaxWidth()
        ) { Text("Update Information") }
        Text("Update phone numbers", fontSize = 12.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Calls", fontWeight = FontWeight.Bold)
        Button(onClick = onCallMom, modifier = Modifier.fillMaxWidth()) { Text("Call Mom") }
        Button(onClick = onCallPSW, modifier = Modifier.fillMaxWidth()) { Text("Call PSW") }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Doors", fontWeight = FontWeight.Bold)
        Button(
            onClick = onOpenApartmentDoor, modifier = Modifier.fillMaxWidth()
        ) { Text("Open Apartment Door") }
        Button(
            onClick = onOpenRoomDoor, modifier = Modifier.fillMaxWidth()
        ) { Text("Open Room Door") }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Made with ❤️ by the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC).",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }

    if (showInfoDialog) {
        InfoDialog(
            currentMomNumber = information.momNumber,
            currentPswNumber = information.pswNumber,
            onDismissRequest = { showInfoDialog = false },
            onConfirmation = { newMom, newPsw ->
                information.update(newMom, newPsw)
                onInformationUpdated()
            },
        )
    }
}