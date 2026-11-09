package com.applooma.rtc

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.applooma.rtc.transport.renderer.TextureViewRenderer
import com.applooma.rtc.transport.room.track.VideoTrack

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
 * A view attached with [attachLocal] follows your camera through every
 * restart — off/on, front/back, a settings change — so a self-preview never
 * stays stuck on the last frame of a track that is gone. A view attached to a
 * remote user follows that user's camera the same way (unpublish/republish,
 * [AppEngine.resubscribe]).
 *
 * The view remembers what it shows. Taking it out of the window (moving it
 * between a small tile and full screen, picture-in-picture, a seat change,
 * a RecyclerView recycling it) pauses drawing; putting it back resumes it
 * without another attach call. [detach] and [release] forget the target.
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
    private var attachedRtc: org.webrtc.VideoTrack? = null
    private var attachedSink = false
    private var initialised = false
    private var waitingFor: AppRemoteUser? = null
    private var waitingLocal: AppEngine? = null
    private var waitingEngine: AppEngine? = null
    // Set while this view shows the local camera; the engine re-binds it on every track change.
    private var localEngine: AppEngine? = null
    // Set while this view shows a remote camera.
    private var remoteEngine: AppEngine? = null

    // What the app asked this view to show, kept across window detach/attach.
    private var targetEngine: AppEngine? = null
    private var targetLocal = false
    private var targetUid: String? = null

    /** The remote uid this view shows, if any. */
    internal val boundUid: String? get() = if (remoteEngine != null) targetUid else null

    private var mirrorOverride: Boolean? = null

    init {
        addView(
            renderer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * Mirror the picture horizontally, on this view only (what is sent is not
     * affected). By default a self-view of the front camera is mirrored, like
     * a mirror, and everything else is not. [resetMirror] returns to that
     * default.
     */
    fun setMirror(mirror: Boolean) {
        mirrorOverride = mirror
        applyMirror()
    }

    /** Back to the default: mirrored for a front-camera self-view only. */
    fun resetMirror() {
        mirrorOverride = null
        applyMirror()
    }

    private fun applyMirror() {
        val local = localEngine
        renderer.setMirror(mirrorOverride ?: (local != null && local.localCameraIsFront))
    }

    /** Renders a remote participant's camera, now or as soon as they publish one. */
    fun attach(engine: AppEngine, user: AppRemoteUser) {
        targetEngine = engine; targetLocal = false; targetUid = user.uid
        bindRemote(engine, user)
    }

    private fun bindRemote(engine: AppEngine, user: AppRemoteUser) {
        clearWait()
        clearLocal()
        if (remoteEngine !== engine) { remoteEngine?.remoteViews?.remove(this); remoteEngine = engine }
        engine.remoteViews.add(this)
        applyMirror()
        val track = user.videoTrack
        if (track == null) {
            waitingFor = user; waitingEngine = engine
            engine.trackWaiters.add(this)
            unbind()
            return
        }
        bind(engine, track)
    }

    /** Renders your own camera, now or as soon as it is on — and again after every restart. */
    fun attachLocal(engine: AppEngine) {
        targetEngine = engine; targetLocal = true; targetUid = null
        bindLocal(engine)
    }

    private fun bindLocal(engine: AppEngine) {
        clearWait()
        clearRemote()
        localEngine = engine
        engine.localViews.add(this)
        applyMirror()
        val track = engine.localVideoTrack
        if (track == null) {
            waitingLocal = engine; waitingEngine = engine
            engine.trackWaiters.add(this)
            unbind()
            return
        }
        bind(engine, track)
    }

    internal fun trackArrived(engine: AppEngine, user: AppRemoteUser) {
        if (waitingFor?.uid == user.uid) bindRemote(engine, user)
    }

    /** The remote user's track was subscribed or unsubscribed: follow it. */
    internal fun remoteTrackChanged(engine: AppEngine, user: AppRemoteUser) {
        if (remoteEngine !== engine || targetUid != user.uid) return
        val track = user.videoTrack
        if (track !== attachedTrack) bindRemote(engine, user)
    }

    internal fun localTrackArrived(engine: AppEngine) {
        if (waitingLocal === engine || localEngine === engine) refreshLocal(engine)
    }

    /**
     * Re-bind to the current local track if it is not the one being drawn.
     * Camera off→on, switchCamera and setVideoConfig all replace the track
     * (or the native track inside it); without this the view kept rendering
     * the dead one.
     */
    internal fun refreshLocal(engine: AppEngine) {
        if (localEngine !== engine) return
        applyMirror()
        val track = engine.localVideoTrack
        if (track == null) { unbind(); return }
        bind(engine, track)
    }

    private fun clearWait() {
        waitingEngine?.trackWaiters?.remove(this)
        waitingFor = null; waitingLocal = null; waitingEngine = null
    }

    private fun clearLocal() {
        localEngine?.localViews?.remove(this)
        localEngine = null
    }

    private fun clearRemote() {
        remoteEngine?.remoteViews?.remove(this)
        remoteEngine = null
    }

    private fun bind(engine: AppEngine, track: VideoTrack) {
        val rtc = try { track.rtcTrack } catch (_: Throwable) { null }
        // Same track and same native track underneath: already drawing.
        if (track === attachedTrack && rtc === attachedRtc) return
        unbind()

        // The renderer needs the engine's EGL context before it can accept
        // frames, and only once per view.
        if (!initialised) {
            engine.initRenderer(renderer)
            initialised = true
        }

        // The engine's local track hands its renderers the raw camera frames,
        // ahead of any processor; draw from the native track instead so the
        // host's own preview shows what viewers get (beauty, filters, effects).
        val local = track is com.applooma.rtc.transport.room.track.LocalVideoTrack && rtc != null
        if (local) rtc!!.addSink(renderer) else track.addRenderer(renderer)
        attachedSink = local
        attachedTrack = track
        attachedRtc = rtc
    }

    private fun unbind() {
        try {
            if (attachedSink) attachedRtc?.removeSink(renderer) else attachedTrack?.removeRenderer(renderer)
        } catch (_: Throwable) {}
        attachedSink = false
        attachedTrack = null
        attachedRtc = null
    }

    /** Stops rendering and forgets the participant, keeping the view usable for another one. */
    fun detach() {
        targetEngine = null; targetLocal = false; targetUid = null
        clearWait()
        clearLocal()
        clearRemote()
        unbind()
    }

    /** Whether frames are being drawn right now. */
    val isRendering: Boolean get() = attachedTrack != null

    /** Releases the renderer's native resources. The view cannot be reused after this. */
    fun release() {
        detach()
        renderer.release()
        initialised = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Back in a window (tile ↔ full screen, PiP, a seat change): resume
        // whatever this view was showing.
        val engine = targetEngine ?: return
        if (targetLocal) bindLocal(engine)
        else targetUid?.let { uid -> engine.getUser(uid)?.let { bindRemote(engine, it) } }
    }

    override fun onDetachedFromWindow() {
        // Pause drawing but keep the target, so re-attaching resumes it.
        clearWait()
        clearLocal()
        clearRemote()
        unbind()
        super.onDetachedFromWindow()
    }
}
