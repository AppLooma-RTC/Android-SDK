package com.applooma.uikit

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.applooma.rtc.AppEngine
import com.applooma.rtc.AppEngineOptions
import com.applooma.rtc.AppEventHandler
import com.applooma.rtc.AppRemoteUser
import com.applooma.rtc.AppVideoView

/**
 * One engine for the lifetime of a screen, released when the screen leaves.
 * Handler callbacks arrive on the main thread, so they can write Compose state.
 */
@Composable
internal fun rememberEngine(appId: String, options: AppEngineOptions, handler: AppEventHandler): AppEngine {
    val context = LocalContext.current
    val engine = remember { AppEngine.create(context.applicationContext, appId, handler, options) }
    DisposableEffect(engine) { onDispose { engine.destroy() } }
    return engine
}

/** A remote user's camera. The view attaches itself once the track arrives. */
@Composable
internal fun RemoteVideo(engine: AppEngine, user: AppRemoteUser, modifier: Modifier = Modifier) {
    key(user.uid) {
        AndroidView(factory = { AppVideoView(it).apply { attach(engine, user) } }, modifier = modifier, onRelease = { it.detach() })
    }
}

/** Your own camera. Follows off→on and camera switches by itself. */
@Composable
internal fun LocalVideo(engine: AppEngine, modifier: Modifier = Modifier) {
    AndroidView(factory = { AppVideoView(it).apply { attachLocal(engine) } }, modifier = modifier, onRelease = { it.detach() })
}

internal val AppRemoteUser.name: String get() = (attributes["name"] as? String) ?: uid

/**
 * Asks for the microphone (and camera) the first time a screen opens, then
 * runs [onGranted]. Denied → [onDenied] so the screen can say why it cannot join.
 */
@Composable
internal fun RequestPermissions(camera: Boolean, onDenied: () -> Unit, onGranted: suspend () -> Unit) {
    val context = LocalContext.current
    val wanted = remember(camera) {
        if (camera) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
        else arrayOf(android.Manifest.permission.RECORD_AUDIO)
    }
    var granted by remember {
        mutableStateOf(wanted.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) granted = true else onDenied()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(wanted) }
    LaunchedEffect(granted) { if (granted) onGranted() }
}
