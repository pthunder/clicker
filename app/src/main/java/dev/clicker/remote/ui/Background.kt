package dev.clicker.remote.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * Personal background. Files are read from the app's own folder on the device and are never
 * part of the source code:
 *
 *   adb push photo.jpg /sdcard/Android/data/dev.clicker.remote/files/background.jpg
 *   adb push intro.mp4 /sdcard/Android/data/dev.clicker.remote/files/background.mp4
 *
 * The video plays once when the app opens, then fades to the photo behind the menu. Using the
 * video's first frame as the photo makes the hand-off seamless.
 */
object BackgroundFiles {
    fun dir(context: Context): File? = context.getExternalFilesDir(null)
    fun photo(context: Context): File? =
        listOf("jpg", "jpeg", "png", "webp").map { File(dir(context), "background.$it") }.firstOrNull { it.isFile }
    fun video(context: Context): File? = File(dir(context), "background.mp4").takeIf { it.isFile }
}

/** The still photo with a darkening gradient so buttons and text stay readable. */
@Composable
fun PhotoBackground(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    val bitmap = remember {
        BackgroundFiles.photo(context)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    } ?: return
    Box(Modifier.fillMaxSize()) {
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x8C000000), Color(0x40000000), Color(0xB3000000))),
            ),
        )
    }
}

/** Plays [file] once, muted and center-cropped, then calls [onFinished]. Pauses with the app. */
@Composable
fun IntroVideo(file: File, onFinished: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val finished = rememberUpdatedState(onFinished)
    // Lifecycle observers get ON_RESUME as soon as they are added; starting before prepare()
    // finishes puts MediaPlayer in its error state, so only resume once prepared.
    val prepared = remember(file) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val player = remember(file) {
        MediaPlayer().apply {
            setDataSource(file.path)
            isLooping = false
            setVolume(0f, 0f)
            setOnCompletionListener { finished.value() }
            setOnErrorListener { _, _, _ -> finished.value(); true }
        }
    }
    DisposableEffect(player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> runCatching { if (player.isPlaying) player.pause() }
                Lifecycle.Event.ON_RESUME -> if (prepared.get()) runCatching { if (!player.isPlaying) player.start() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        player.setSurface(Surface(st))
                        player.setOnPreparedListener { mp ->
                            prepared.set(true)
                            centerCrop(this@apply, mp.videoWidth, mp.videoHeight)
                            mp.start()
                        }
                        runCatching { player.prepareAsync() }.onFailure { finished.value() }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        centerCrop(this@apply, player.videoWidth, player.videoHeight)
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

private fun centerCrop(view: TextureView, videoW: Int, videoH: Int) {
    if (videoW == 0 || videoH == 0 || view.width == 0 || view.height == 0) return
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    // TextureView stretches the video to the view; undo that and scale to cover.
    val scale = maxOf(viewW / videoW, viewH / videoH)
    view.setTransform(Matrix().apply { setScale(videoW * scale / viewW, videoH * scale / viewH, viewW / 2, viewH / 2) })
}
