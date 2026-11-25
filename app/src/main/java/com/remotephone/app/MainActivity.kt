package com.remotephone.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private enum class Screen { HOME, HOST, CONTROL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RemotePhoneApp() }
    }
}

@Composable
private fun RemotePhoneApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(
            onHost = { screen = Screen.HOST },
            onControl = { screen = Screen.CONTROL },
        )
        Screen.HOST -> HostScreen(onBack = { screen = Screen.HOME })
        Screen.CONTROL -> ControlScreen(onBack = { screen = Screen.HOME })
    }
}

@Composable
private fun HomeScreen(onHost: () -> Unit, onControl: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("RemotePhone", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Control one phone from another, over your own Wi-Fi. No root, no account, no cloud.")
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("What it can and cannot do", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "While a session is live the controlled phone shows a persistent " +
                        "notification and can stop it any time. It cannot unlock the lock " +
                        "screen, and it cannot touch some secure system dialogs (for example " +
                        "permission prompts) — those need a finger on that phone.",
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) {
            Text("This phone is controlled")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onControl, modifier = Modifier.fillMaxWidth()) {
            Text("Control another phone")
        }
    }
}

private fun isA11yEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

@Composable
private fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by HostSession.state.collectAsState()
    val status by HostSession.status.collectAsState()
    val hostIp by HostSession.hostIp.collectAsState()
    val pairCode by HostSession.pairCode.collectAsState()
    var a11yEnabled by remember { mutableStateOf(isA11yEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) a11yEnabled = isA11yEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        OutlinedButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(16.dp))
        Text("This phone is controlled", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (!a11yEnabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Step 1 — turn on the RemotePhone accessibility service", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Without it, taps and swipes from the other phone do nothing. You only need to do this once.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Open Accessibility Settings") }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text("✓ Accessibility service is on — taps and swipes will work.")
            Spacer(Modifier.height(16.dp))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            OutlinedButton(onClick = {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Allow session notifications") }
            Spacer(Modifier.height(16.dp))
        }

        when (state) {
            HostState.IDLE -> {
                Button(
                    onClick = {
                        context.startForegroundService(
                            Intent(context, RemoteSessionService::class.java)
                                .setAction(RemoteSessionService.ACTION_START_HOST),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start hosting") }
                Spacer(Modifier.height(12.dp))
                Text("Both phones must be on the same Wi-Fi network.")
            }
            else -> {
                Text("Your Wi-Fi IP", fontWeight = FontWeight.Bold)
                Text(hostIp, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Pairing code", fontWeight = FontWeight.Bold)
                Text(pairCode, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Status: $status")
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        context.startService(
                            Intent(context, RemoteSessionService::class.java)
                                .setAction(RemoteSessionService.ACTION_STOP),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop hosting") }
                Spacer(Modifier.height(12.dp))
                Text("Screen capture is only asked for after you accept a connection.")
            }
        }
    }
}

@Composable
private fun ControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember { ControllerSession() }
    val state by ControllerSessionState.state.collectAsState()
    val status by ControllerSessionState.status.collectAsState()
    val videoWidth by ControllerSessionState.videoWidth.collectAsState()
    val videoHeight by ControllerSessionState.videoHeight.collectAsState()

    var hostIp by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var typeText by remember { mutableStateOf("") }
    val surfaceState = remember { mutableStateOf<android.view.Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose { session.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                session.disconnect()
                onBack()
            }) { Text("← Back") }
            Spacer(Modifier.height(8.dp))
            Text("Control another phone", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (state != ControllerState.CONNECTED) {
            OutlinedTextField(
                value = hostIp,
                onValueChange = { hostIp = it.trim() },
                label = { Text("Controlled phone's Wi-Fi IP") },
                placeholder = { Text("192.168.1.50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
                label = { Text("6-digit pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { session.connect(hostIp, code) { surfaceState.value } },
                enabled = hostIp.isNotBlank() && code.length == 6 &&
                    state != ControllerState.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state == ControllerState.CONNECTING) "Connecting…" else "Connect") }
            Spacer(Modifier.height(12.dp))
            if (status.isNotEmpty()) Text(status)
        } else {
            // Live video.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (videoWidth > 0 && videoHeight > 0) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surfaceState.value = holder.surface
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder, format: Int, w: Int, h: Int,
                                    ) {
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        surfaceState.value = null
                                    }
                                })
                                var downX = 0f
                                var downY = 0f
                                var downTime = 0L
                                setOnTouchListener { v, event ->
                                    when (event.action) {
                                        MotionEvent.ACTION_DOWN -> {
                                            downX = event.x
                                            downY = event.y
                                            downTime = event.eventTime
                                        }
                                        MotionEvent.ACTION_UP -> {
                                            val dx = event.x - downX
                                            val dy = event.y - downY
                                            val dt = (event.eventTime - downTime).toInt()
                                            val nx1 = (downX / v.width).coerceIn(0f, 1f)
                                            val ny1 = (downY / v.height).coerceIn(0f, 1f)
                                            val nx2 = (event.x / v.width).coerceIn(0f, 1f)
                                            val ny2 = (event.y / v.height).coerceIn(0f, 1f)
                                            if (dt < 250 && dx * dx + dy * dy < 900) {
                                                session.sendTap(nx1, ny1)
                                            } else {
                                                session.sendSwipe(nx1, ny1, nx2, ny2, dt.coerceIn(100, 1000))
                                            }
                                        }
                                    }
                                    true
                                }
                            }
                        },
                        modifier = Modifier.aspectRatio(videoWidth.toFloat() / videoHeight.toFloat()),
                    )
                } else {
                    Text("Waiting for video…")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { session.sendKey("back") }, modifier = Modifier.weight(1f)) { Text("Back") }
                Button(onClick = { session.sendKey("home") }, modifier = Modifier.weight(1f)) { Text("Home") }
                Button(onClick = { session.sendKey("recents") }, modifier = Modifier.weight(1f)) { Text("Recents") }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = typeText,
                    onValueChange = { typeText = it },
                    label = { Text("Type on the other phone") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    session.sendText(typeText)
                    typeText = ""
                }) { Text("Send") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { session.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect") }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(status)
            }
        }
    }
}
