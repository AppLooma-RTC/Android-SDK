package com.applooma.rtc

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack

/**
 * Renders a participant's camera.
 *
 * Add it to your layout like any other view, then attach a participant:
 *
 * ```kotlin
 * val tile = AppVideoView(context)
 * container.addView(tile)
 *
 * // A participant is announced before their camera arrives, so attach here
 * // rather than in onUserJoined, or the tile stays black.
 * override fun onTrackSubscribed(user: AppRemoteUser) {
 *     tile.attach(engine, user)
 * }
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

    init {
        addView(
            renderer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** Renders a remote participant's camera. Does nothing until they publish one. */
    fun attach(engine: AppEngine, user: AppRemoteUser) {
        attach(engine, user.videoTrack)
    }

    /** Renders your own camera. */
    fun attachLocal(engine: AppEngine) {
        attach(engine, engine.localVideoTrack)
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

    /** Releases the renderer's native resources. The view cannot be reused after this. */
    fun release() {
        detach()
        renderer.release()
        initialised = false
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }
}
