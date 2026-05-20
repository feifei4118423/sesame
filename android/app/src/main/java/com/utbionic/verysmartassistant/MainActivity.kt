package com.utbionic.verysmartassistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.core.net.toUri
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()

    private val scope = MainScope()
    private lateinit var deviceManager: DeviceManager
    private var isControllerConnected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deviceManager = DeviceManager(scope, information)
        handleIntent(intent)

        setContent {
            VerySmartAssistantTheme {
                Home(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp),
                    information = information,
                    isControllerConnected = isControllerConnected,
                    onSetup = {
                        setup()
                    },
                    onCallMom = {
                        call(information.momNumber)
                    },
                    onCallPSW = {
                        call(information.pswNumber)
                    },
                    onOpenApartmentDoor = { openDoor("APARTMENT") },
                    onOpenRoomDoor = { openDoor("ROOM") },
                    onInformationUpdated = { showMessage("Information updated") },
                )
            }
        }
    }

    override fun onDestroy() {
        deviceManager.stopHeartbeat()
        super.onDestroy()
        scope.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setup() {
        showMessage("Attempting to connect to controller...")
        deviceManager.startHeartbeat(60_000) { heartbeatSuccess ->
            val wasConnected = isControllerConnected
            isControllerConnected = heartbeatSuccess

            if (!heartbeatSuccess && wasConnected) {
                showMessage("Connection lost.")
            }
            if (heartbeatSuccess && !wasConnected) {
                showMessage("Connection restored.")
            }
        }
    }

    private fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = ("tel:$phoneNumber").toUri()
        }
        startActivity(intent)
    }

    private fun openDoor(target: String) {
        showMessage("Attempting to open $target")

        val command = when (target) {
            "APARTMENT" -> {
                "OPEN_APARTMENT"
            }

            "ROOM" -> {
                "OPEN_ROOM"
            }

            else -> {
                showMessage("Invalid target: $target")
                return
            }
        }

        deviceManager.sendCommand(command) { success ->
            val message = if (success) {
                "Successfully opened $target"
            } else {
                "Error occurred while attempting to open $target"
            }

            showMessage(message)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun handleIntent(incoming: Intent) {
        if (incoming.action != Intent.ACTION_VIEW) return

        val uri = incoming.data
        val doorFromUri = uri?.getQueryParameter("door_type")
        val doorExtra = incoming.getStringExtra("door_type")
        val contactFromUri = uri?.getQueryParameter("contact")
        val contactExtra = incoming.getStringExtra("contact")
        val feature = incoming.getStringExtra("feature")

        val doorType = when {
            !doorFromUri.isNullOrBlank() -> doorFromUri
            !doorExtra.isNullOrBlank() -> doorExtra
            !feature.isNullOrBlank() && feature.contains(
                "apartment", ignoreCase = true
            ) -> "apartment"

            !feature.isNullOrBlank() && feature.contains("room", ignoreCase = true) -> "room"
            else -> null
        }

        val contactType = when {
            !contactFromUri.isNullOrBlank() -> contactFromUri
            !contactExtra.isNullOrBlank() -> contactExtra
            !feature.isNullOrBlank() && feature.contains("mom", ignoreCase = true) -> "mom"
            !feature.isNullOrBlank() && feature.contains("psw", ignoreCase = true) -> "psw"
            else -> null
        }

        if (doorType != null) {
            openDoor(doorType)
        } else if (contactType != null) {
            when (contactType.lowercase()) {
                "mom" -> call(information.momNumber)
                "psw" -> call(information.pswNumber)
            }
        }
    }
}

@Composable
fun Home(
    modifier: Modifier = Modifier,
    information: Information,
    isControllerConnected: Boolean,
    onSetup: () -> Unit,
    onCallMom: () -> Unit,
    onCallPSW: () -> Unit,
    onOpenApartmentDoor: () -> Unit,
    onOpenRoomDoor: () -> Unit,
    onInformationUpdated: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.verticalScroll(scrollState)) {
        Text("Very Smart Assistant", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Setup", fontWeight = FontWeight.Bold)
        Text("Mom Phone Number: ${information.momNumber}")
        Text("PSW Phone Number: ${information.pswNumber}")
        Text("Controller Address: ${information.controllerAddress}")
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

        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup") }
        Text(
            "Checks the controller connection", fontSize = 12.sp, color = Color.Gray
        )

        Button(
            onClick = { showInfoDialog = true }, modifier = Modifier.fillMaxWidth()
        ) { Text("Update Information") }
        Text("Update phone numbers and controller address", fontSize = 12.sp, color = Color.Gray)

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
            currentControllerAddress = information.controllerAddress,
            onDismissRequest = { showInfoDialog = false },
            onConfirmation = { newMom, newPsw, newAddr ->
                information.update(newMom, newPsw, newAddr)
                onInformationUpdated()
            },
        )
    }
}

