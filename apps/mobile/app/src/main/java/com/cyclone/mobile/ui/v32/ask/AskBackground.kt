package com.cyclone.mobile.ui.v32.ask

import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The AI screen's background choice (R3): Cyclone's own rain (the default), or a video the owner picked from their
 * phone. The video is never copied or uploaded: Cyclone keeps only Android's read grant for the file the owner chose,
 * and plays it muted and looped behind the glass. The owner can go back to the rain at any time.
 */
object AskBackground {
    private const val PREFS = "cyclone_ai"
    private const val KEY = "ask_background_video"

    private val _video = MutableStateFlow<String?>(null)
    /** The picked video's content URI, or null for the rain. */
    val video: StateFlow<String?> = _video
    @Volatile private var loaded = false

    fun load(context: Context): String? {
        if (!loaded) {
            _video.value = prefs(context).getString(KEY, null)
            loaded = true
        }
        return _video.value
    }

    /** Keeps the owner's pick across restarts: Android's persistable read grant, then the URI. */
    fun choose(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs(context).edit().putString(KEY, uri.toString()).apply()
        _video.value = uri.toString()
        loaded = true
    }

    /** Back to the rain; the read grant is handed back to Android. */
    fun useRain(context: Context) {
        _video.value?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs(context).edit().remove(KEY).apply()
        _video.value = null
        loaded = true
    }

    /** The video could not be read (deleted, grant gone): fall back to the rain for this session. */
    internal fun failed(uri: String) {
        if (_video.value == uri) _video.value = null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Centre-crop: the scale that makes a [videoW]×[videoH] video cover a [viewW]×[viewH] view. Pure, tested. */
    fun coverScale(viewW: Float, viewH: Float, videoW: Float, videoH: Float): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || videoW <= 0f || videoH <= 0f) return 1f to 1f
        val scale = maxOf(viewW / videoW, viewH / videoH)
        return (videoW * scale / viewW) to (videoH * scale / viewH)
    }
}

/**
 * The owner's video behind the glass: a TextureView (so the glass can blur it like the rain), muted, looping,
 * centre-cropped, paused while the app is in the background, and still with Android animations off. If the file
 * cannot be played, the page goes back to the rain.
 */
@Composable
fun AskVideoField(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The same lifecycle lookup as the app shell (CycloneV32App): the hosting activity.
    val lifecycle = remember(context) { (context as? LifecycleOwner)?.lifecycle }
    val still = remember { AskRain.animationsOff(context) }
    val player = remember(uri) { MediaPlayer() }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> if (player.isPlaying) player.pause()
                    Lifecycle.Event.ON_RESUME -> if (!still && !player.isPlaying) player.start()
                    else -> Unit
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            runCatching { player.release() }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextureView(viewContext).apply {
                fun crop(videoW: Int, videoH: Int) {
                    val (sx, sy) = AskBackground.coverScale(width.toFloat(), height.toFloat(), videoW.toFloat(), videoH.toFloat())
                    setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setDataSource(viewContext, Uri.parse(uri))
                            player.setSurface(Surface(texture))
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setOnVideoSizeChangedListener { _, videoW, videoH -> crop(videoW, videoH) }
                            player.setOnErrorListener { _, _, _ ->
                                AskBackground.failed(uri)
                                true
                            }
                            player.setOnPreparedListener { prepared ->
                                crop(prepared.videoWidth, prepared.videoHeight)
                                if (still) prepared.seekTo(0) else prepared.start()
                            }
                            player.prepareAsync()
                        }.onFailure { AskBackground.failed(uri) }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                        runCatching { crop(player.videoWidth, player.videoHeight) }
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}
