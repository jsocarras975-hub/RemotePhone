package com.remotephone.app

import org.json.JSONObject

/**
 * Control-channel protocol (TCP 18081, one JSON object per line, UTF-8).
 * Touch coordinates are normalized 0..1 relative to the host's capture size.
 */
object Protocol {
    const val VIDEO_PORT = 18080
    const val CONTROL_PORT = 18081

    fun hello(code: String): String =
        JSONObject().put("type", "hello").put("code", code).toString()

    fun ready(width: Int, height: Int): String =
        JSONObject().put("type", "ready").put("width", width).put("height", height).toString()

    fun denied(reason: String): String =
        JSONObject().put("type", "denied").put("reason", reason).toString()

    fun tap(x: Float, y: Float): String =
        JSONObject().put("type", "tap").put("x", x.toDouble()).put("y", y.toDouble()).toString()

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): String =
        JSONObject().put("type", "swipe")
            .put("x1", x1.toDouble()).put("y1", y1.toDouble())
            .put("x2", x2.toDouble()).put("y2", y2.toDouble())
            .put("duration", durationMs).toString()

    fun key(action: String): String =
        JSONObject().put("type", "key").put("action", action).toString()

    fun text(text: String): String =
        JSONObject().put("type", "text").put("text", text).toString()

    fun bye(): String = JSONObject().put("type", "bye").toString()

    /** Split an Annex-B byte stream (00 00 00 01 / 00 00 01 prefixed NALs) into raw NAL units. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                ((data[i + 2] == 1.toByte()) ||
                    (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) {
                val startLen = if (data[i + 2] == 1.toByte()) 3 else 4
                starts.add(i + startLen)
                i += startLen
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return listOf(data)
        val out = ArrayList<ByteArray>()
        for (idx in starts.indices) {
            val from = starts[idx]
            var to = data.size
            if (idx + 1 < starts.size) {
                // End of this NAL is just before the next start code (3 or 4 bytes back).
                to = starts[idx + 1] - if (starts[idx + 1] >= 4 &&
                    data[starts[idx + 1] - 4] == 0.toByte() &&
                    data[starts[idx + 1] - 3] == 0.toByte() &&
                    data[starts[idx + 1] - 2] == 0.toByte()) 4 else 3
            }
            if (to > from) out.add(data.copyOfRange(from, to))
        }
        return out
    }
}
