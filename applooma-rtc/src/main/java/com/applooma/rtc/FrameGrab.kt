package com.applooma.rtc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Takes one frame off a video track and hands it back as a [Bitmap], upright.
 *
 * Nothing leaves the phone: this is the primitive behind a host presence
 * check (is a real person in frame?) done entirely on device, the way a
 * `takeSnapshot(uid 0)` call is used elsewhere. The sink detaches itself
 * after the first frame, so the cost is one conversion, not a stream.
 */
internal class FrameGrab(private val track: VideoTrack, private val maxEdge: Int, private val onFrame: (Bitmap?) -> Unit) : VideoSink {
    private val done = AtomicBoolean(false)

    fun start() {
        track.addRenderer(this)
    }

    override fun onFrame(frame: VideoFrame) {
        if (!done.compareAndSet(false, true)) return
        val bitmap = try { toBitmap(frame) } catch (_: Throwable) { null }
        // Detach off the render thread; the track lock is held here.
        Thread { try { track.removeRenderer(this) } catch (_: Throwable) {} }.start()
        onFrame(bitmap)
    }

    private fun toBitmap(frame: VideoFrame): Bitmap? {
        val i420 = frame.buffer.toI420() ?: return null
        try {
            val w = i420.width
            val h = i420.height
            // I420 → NV21 (Y plane, then interleaved V/U), which YuvImage can encode.
            val nv21 = ByteArray(w * h * 3 / 2)
            val y = i420.dataY; val u = i420.dataU; val v = i420.dataV
            val sy = i420.strideY; val su = i420.strideU; val sv = i420.strideV
            var o = 0
            for (row in 0 until h) { y.position(row * sy); y.get(nv21, o, w); o += w }
            val cw = (w + 1) / 2; val ch = (h + 1) / 2
            for (row in 0 until ch) {
                for (col in 0 until cw) {
                    nv21[o++] = v.get(row * sv + col)
                    nv21[o++] = u.get(row * su + col)
                }
            }
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
            val bytes = out.toByteArray()
            // Downscale on decode: a presence check needs ~480 px, not 1080.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxEdge > 0 && maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (frame.rotation != 0) {
                val m = Matrix().apply { postRotate(frame.rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            return bmp
        } finally {
            i420.release()
        }
    }
}
