package com.remotephone.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the controlled phone when a controller connects with the right
 * pairing code. Nothing is captured until the user taps Accept AND approves
 * the system screen-capture prompt that follows.
 */
class PairRequestActivity : ComponentActivity() {

    private var peerIp: String = "?"
    private var answered = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            answered = true
            startService(
                Intent(this, RemoteSessionService::class.java).apply {
                    action = RemoteSessionService.ACTION_PROJECTION_RESULT
                    putExtra(RemoteSessionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(RemoteSessionService.EXTRA_RESULT_DATA, result.data)
                },
            )
        } else {
            decline()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerIp = intent.getStringExtra(RemoteSessionService.EXTRA_PEER_IP) ?: "?"
        setContent { PairRequestScreen(peerIp, onAccept = { accept() }, onDecline = { decline(); finish() }) }
    }

    override fun onBackPressed() {
        decline()
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (!answered) decline()
        super.onDestroy()
    }

    private fun accept() {
        answered = true
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun decline() {
        if (answered) return
        answered = true
        startService(
            Intent(this, RemoteSessionService::class.java).apply {
                action = RemoteSessionService.ACTION_DECLINE
            },
        )
    }
}

@Composable
private fun PairRequestScreen(peerIp: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Remote control request", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("A phone at $peerIp wants to see your screen and control this phone.")
        Spacer(Modifier.height(8.dp))
        Text("Only accept if you know who this is. You can stop the session any time from the notification.")
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Decline") }
        }
    }
}
