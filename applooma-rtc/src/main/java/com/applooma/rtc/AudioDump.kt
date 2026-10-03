package com.applooma.rtc

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only: with `adb shell setprop debug.applooma.dumpaudio 1`, writes the first
 * 20 s of microphone audio before processing (raw.pcm) and after processing,
 * as it is handed to the encoder (sent.pcm), to the app's files directory as
 * 16-bit mono PCM, so the stages can be compared by ear and measured.
 */
internal object AudioDump {
    @Volatile var dir: File? = null
    private class Sink(val name: String) {
        var out: FileOutputStream? = null; var written = 0L; var rate = 0; var done = false
        fun write(samples: FloatArray, n: Int, sampleRate: Int) {
            if (done) return
            val d = dir ?: return
            if (out == null) { rate = sampleRate; out = FileOutputStream(File(d, "$name.pcm")) }
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort((samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            out!!.write(bb.array()); written += n
            if (written >= rate * 20L) { out!!.close(); done = true; AppEngine.debug("audio dump $name.pcm rate=$rate samples=$written") }
        }
    }
    private val raw = Sink("raw"); private val sent = Sink("sent")
    val enabled: Boolean by lazy { MicCapture.flag("dumpaudio", false) }

    fun raw(data: ByteBuffer, sampleRate: Int, channels: Int, bytes: Int) {
        if (!enabled || raw.done) return
        val sb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN); sb.position(0)
        val frames = bytes / 2 / channels.coerceAtLeast(1)
        val f = FloatArray(frames)
        for (i in 0 until frames) { if (sb.remaining() < 2 * channels) break; f[i] = sb.short / 32768f; for (c in 1 until channels) sb.short }
        synchronized(raw) { raw.write(f, frames, sampleRate) }
    }

    fun sent(fb: java.nio.FloatBuffer, n: Int, sampleRate: Int) {
        if (!enabled || sent.done) return
        var peak = 0f; for (i in 0 until n) { val a = kotlin.math.abs(fb.get(i)); if (a > peak) peak = a }
        val sc = if (peak > 2f) 32768f else 1f
        val f = FloatArray(n) { fb.get(it) / sc }
        synchronized(sent) { sent.write(f, n, sampleRate) }
    }
}
