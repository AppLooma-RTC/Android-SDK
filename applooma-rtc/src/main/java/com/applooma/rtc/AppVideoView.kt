package com.applooma.rtc

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack

/**
 * Renders a participant's camera.
 *
 * Add it to your layout like any other view, then attach a participant.
 * Attach as early as you like — in onUserJoined, or even before you join for
 * your own preview: if the camera has not arrived yet, the view waits and
 * starts drawing the moment it does.
 *
 * ```kotlin
 * val tile = AppVideoView(context)
 * container.addView(tile)
 * override fun onUserJoined(user: AppRemoteUser) { tile.attach(engine, user) }
 * ```
 *
 * Call [release] when the view goes away — a renderer holds native resources
 * that are not freed by garbage collection.
 */
class AppVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val renderer = TextureViewRenderer(context)
    private var attachedTrack: VideoTrack? = null
    private var initialised = false
    private var waitingFor: AppRemoteUser? = null
    private var waitingLocal: AppEngine? = null
    private var waitingEngine: AppEngine? = null

    init {
        addView(
            renderer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** Renders a remote participant's camera, now or as soon as they publish one. */
    fun attach(engine: AppEngine, user: AppRemoteUser) {
        clearWait()
        val track = user.videoTrack
        if (track == null) {
            waitingFor = user; waitingEngine = engine
            engine.trackWaiters.add(this)
            detach()
            return
        }
        attach(engine, track)
    }

    /** Renders your own camera, now or as soon as it is on. */
    fun attachLocal(engine: AppEngine) {
        clearWait()
        val track = engine.localVideoTrack
        if (track == null) {
            waitingLocal = engine; waitingEngine = engine
            engine.trackWaiters.add(this)
            detach()
            return
        }
        attach(engine, track)
    }

    internal fun trackArrived(engine: AppEngine, user: AppRemoteUser) {
        if (waitingFor?.uid == user.uid) attach(engine, user)
    }

    internal fun localTrackArrived(engine: AppEngine) {
        if (waitingLocal === engine) attachLocal(engine)
    }

    private fun clearWait() {
        waitingEngine?.trackWaiters?.remove(this)
        waitingFor = null; waitingLocal = null; waitingEngine = null
    }

    private fun attach(engine: AppEngine, track: VideoTrack?) {
        if (track === attachedTrack) return
        detach()
        if (track == null) return

        // The renderer needs the engine's EGL context before it can accept
        // frames, and only once per view.
        if (!initialised) {
            engine.initRenderer(renderer)
            initialised = true
        }

        track.addRenderer(renderer)
        attachedTrack = track
    }

    /** Stops rendering, keeping the view usable for another participant. */
    fun detach() {
        attachedTrack?.removeRenderer(renderer)
        attachedTrack = null
    }

    /** Whether frames are being drawn right now. */
    val isRendering: Boolean get() = attachedTrack != null

    /** Releases the renderer's native resources. The view cannot be reused after this. */
    fun release() {
        detach()
        renderer.release()
        initialised = false
    }

    override fun onDetachedFromWindow() {
        clearWait()
        detach()
        super.onDetachedFromWindow()
    }
}
