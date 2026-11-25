package com.remotephone.app

import kotlinx.coroutines.flow.MutableStateFlow

enum class HostState {
    IDLE, LISTENING, WAITING_ACCEPT, STREAMING
}

enum class ControllerState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR
}

/** Shared host-side session state, updated by RemoteSessionService, read by the UI. */
object HostSession {
    val state = MutableStateFlow(HostState.IDLE)
    val status = MutableStateFlow("")
    val hostIp = MutableStateFlow("")
    val pairCode = MutableStateFlow("")
    val peerIp = MutableStateFlow("")
    val captureSize = MutableStateFlow(Pair(0, 0))

    fun reset() {
        state.value = HostState.IDLE
        status.value = ""
        hostIp.value = ""
        pairCode.value = ""
        peerIp.value = ""
        captureSize.value = Pair(0, 0)
    }
}

/** Shared controller-side session state, updated by ControllerSession, read by the UI. */
object ControllerSessionState {
    val state = MutableStateFlow(ControllerState.IDLE)
    val status = MutableStateFlow("")
    val videoWidth = MutableStateFlow(0)
    val videoHeight = MutableStateFlow(0)
}
