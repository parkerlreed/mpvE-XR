package app.marlboroadvance.mpvex.ui.player.xr

import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.MPVView
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.resolveUri
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Immersive Quest player: passthrough room with the video on a CRT the user can grab and place.
 *
 * mpv is configured by the same [MPVView] options as the phone player, but renders into a
 * SurfaceTexture owned by the native OpenXR renderer instead of an on-screen SurfaceView.
 */
class XrPlayerActivity : ComponentActivity() {
  object Action {
    const val TOGGLE_PAUSE = 1
    const val SEEK_BACK = 2
    const val SEEK_FORWARD = 3
    const val VOLUME_UP = 4
    const val VOLUME_DOWN = 5
    const val SHOW_PROGRESS = 6
    const val CYCLE_SUBTITLES = 7
    const val CYCLE_AUDIO = 8
    const val EXIT = 9
    const val PAUSE = 10
    const val SEEK_BACK_LONG = 11
    const val SEEK_FORWARD_LONG = 12
    const val STOP = 13
  }

  private val playerPreferences: PlayerPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val poseStore by lazy { getSharedPreferences(POSE_PREFS, MODE_PRIVATE) }

  private lateinit var player: MPVView
  private var mpvInitialized = false
  private var renderThread: Thread? = null

  // Owned by the render thread.
  private var surfaceTexture: SurfaceTexture? = null
  private var surface: Surface? = null
  private val frameAvailable = AtomicBoolean(false)
  private var hasFrame = false

  @Volatile private var playing = false
  @Volatile private var pendingPath: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val path = playablePath(intent)
    if (path == null) {
      Toast.makeText(this, R.string.xr_player_unsupported_source, Toast.LENGTH_SHORT).show()
      finish()
      return
    }

    runCatching { Utils.copyAssets(this) }
      .onFailure { Log.e(TAG, "Error copying mpv assets", it) }
    player = layoutInflater.inflate(R.layout.xr_mpv_holder, null) as MPVView
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    MPVLib.addObserver(observer)
    applyHttpHeaders(intent)
    pendingPath = path

    renderThread = thread(name = "mpvEx-XR") {
      val ok = XrNative.run(this, bridge, loadCrtPose(), customModel()?.path)
      if (!ok) runOnUiThread { fallBackTo2d() }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val path = playablePath(intent) ?: return
    applyHttpHeaders(intent)
    MPVLib.command("loadfile", path)
  }

  override fun onStop() {
    if (mpvInitialized) MPVLib.setPropertyBoolean("pause", true)
    super.onStop()
  }

  override fun onDestroy() {
    XrNative.requestExit()
    renderThread?.join(RENDER_THREAD_JOIN_MS)
    renderThread = null
    destroyMpv()
    super.onDestroy()
  }

  private fun destroyMpv() {
    if (!mpvInitialized) return
    runCatching {
      MPVLib.removeObserver(observer)
      MPVLib.setPropertyBoolean("pause", true)
      MPVLib.command("quit")
      // Same grace period PlayerActivity gives mpv's threads before tearing it down.
      Thread.sleep(100)
      player.destroy()
    }.onFailure { Log.e(TAG, "Error destroying mpv", it) }
    mpvInitialized = false
  }

  /** OpenXR is unavailable (e.g. headset runtime missing); play in the regular player instead. */
  private fun fallBackTo2d() {
    // MPVLib is a singleton, so it must be gone before PlayerActivity creates it again.
    destroyMpv()
    Toast.makeText(this, R.string.xr_player_unavailable, Toast.LENGTH_SHORT).show()
    startActivity(
      Intent(intent)
        .setClass(this, PlayerActivity::class.java)
        .putExtra(XrSupport.EXTRA_FORCE_2D, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    finish()
  }

  private val bridge = object : XrBridge {
    override fun onGlReady(texture: Int, screenAspect: Float) {
      // Match the glass so mpv letterboxes against the real screen shape; keep the width even.
      val width = ((SURFACE_HEIGHT * screenAspect).toInt() / 2 * 2).coerceIn(SURFACE_HEIGHT, SURFACE_HEIGHT * 2)
      val st = SurfaceTexture(texture).apply {
        setDefaultBufferSize(width, SURFACE_HEIGHT)
        setOnFrameAvailableListener({ frameAvailable.set(true) }, Handler(Looper.getMainLooper()))
      }
      val s = Surface(st)
      surfaceTexture = st
      surface = s

      // Equivalent of BaseMPVView.surfaceCreated, with our texture-backed surface.
      MPVLib.attachSurface(s)
      MPVLib.setOptionString("force-window", "yes")
      MPVLib.setPropertyString("android-surface-size", "${width}x$SURFACE_HEIGHT")
      pendingPath?.let { MPVLib.command("loadfile", it) }
      pendingPath = null
    }

    override fun updateVideoTexture(matrix: FloatArray): Boolean {
      val st = surfaceTexture ?: return false
      if (frameAvailable.getAndSet(false)) {
        st.updateTexImage()
        hasFrame = true
      }
      st.getTransformMatrix(matrix)
      return hasFrame
    }

    override fun isPlaying(): Boolean = playing

    override fun onAction(code: Int) {
      runOnUiThread { handleAction(code) }
    }

    override fun onCrtPoseChanged(pose: FloatArray) {
      poseStore.edit { putString(KEY_CRT_POSE, pose.joinToString(",")) }
    }

    override fun onSessionEnded() {
      if (surface != null) {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
      }
      surface?.release()
      surfaceTexture?.release()
      surface = null
      surfaceTexture = null
      runOnUiThread { if (!isFinishing) finish() }
    }
  }

  private fun handleAction(code: Int) {
    if (!mpvInitialized) return
    when (code) {
      Action.TOGGLE_PAUSE -> {
        MPVLib.command("cycle", "pause")
        MPVLib.command("show-text", "\${?pause==yes:Paused}\${?pause==no:Playing}")
      }
      Action.SEEK_BACK -> seek(-SEEK_SECONDS)
      Action.SEEK_FORWARD -> seek(SEEK_SECONDS)
      Action.SEEK_BACK_LONG -> seek(-LONG_SEEK_SECONDS)
      Action.SEEK_FORWARD_LONG -> seek(LONG_SEEK_SECONDS)
      Action.STOP -> {
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.command("show-text", "Stop")
      }
      Action.VOLUME_UP -> changeVolume(VOLUME_STEP)
      Action.VOLUME_DOWN -> changeVolume(-VOLUME_STEP)
      Action.SHOW_PROGRESS -> MPVLib.command("show-progress")
      Action.CYCLE_SUBTITLES -> {
        MPVLib.command("cycle", "sub")
        MPVLib.command("show-text", "Subtitles: \${sid} \${?current-tracks/sub/title:\${current-tracks/sub/title}}")
      }
      Action.CYCLE_AUDIO -> {
        MPVLib.command("cycle", "audio")
        MPVLib.command("show-text", "Audio: \${aid} \${?current-tracks/audio/title:\${current-tracks/audio/title}}")
      }
      Action.EXIT -> finish()
      Action.PAUSE -> MPVLib.setPropertyBoolean("pause", true)
    }
  }

  private fun seek(seconds: Int) {
    MPVLib.command("seek", seconds.toString(), "relative")
    MPVLib.command("show-progress")
  }

  private fun changeVolume(step: Int) {
    MPVLib.command("add", "volume", step.toString())
    MPVLib.command("show-text", "Volume: \${volume}%")
  }

  private val observer = object : MPVLib.EventObserver {
    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Boolean) {
      when (property) {
        "pause" -> playing = !value
        "eof-reached" -> if (value && playerPreferences.closeAfterReachingEndOfVideo.get()) {
          runOnUiThread { finish() }
        }
      }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {}

    override fun eventProperty(property: String, value: MPVNode) {}

    override fun event(eventId: Int, data: MPVNode) {
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
        runOnUiThread { onFileLoaded() }
      }
    }
  }

  private fun onFileLoaded() {
    playing = MPVLib.getPropertyBoolean("pause") == false
    if (!subtitlesPreferences.autoloadMatchingSubtitles.get()) return
    val current = intent
    val fileName = current.getStringExtra("title") ?: current.getStringExtra("filename")
      ?: current.data?.lastPathSegment ?: return
    lifecycleScope.launch {
      // Mirrors PlayerActivity: network files look for subtitles next to the original remote path.
      val networkPath = current.getStringExtra("network_file_path")
      val connectionId = current.getLongExtra("network_connection_id", -1L)
      if (networkPath != null && connectionId != -1L) {
        SubtitleOps.autoloadSubtitles(networkPath, fileName, connectionId)
      } else {
        playablePath(current)?.let { SubtitleOps.autoloadSubtitles(it, fileName) }
      }
    }
  }

  /** Same resolution as PlayerActivity: file paths, content:// via fd, and network URLs. */
  private fun playablePath(intent: Intent): String? =
    when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
      Intent.ACTION_SEND -> sharedUri(intent)?.resolveUri(this)
      else -> intent.getStringExtra("uri")
    }

  private fun sharedUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(Intent.EXTRA_STREAM)
    } ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let(Uri::parse)

  /** Referer for network streams plus any headers passed by the caller, as in PlayerActivity. */
  private fun applyHttpHeaders(intent: Intent) {
    val headers = mutableMapOf<String, String>()
    val uri = intent.data
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { headers["Referer"] = it }
    }
    intent.extras?.getStringArray("headers")?.let { extra ->
      if (extra.size >= 2 && extra[0].startsWith("User-Agent", ignoreCase = true)) {
        MPVLib.setPropertyString("user-agent", extra[1])
      }
      extra.drop(2).chunked(2).filter { it.size == 2 }.forEach { (k, v) -> headers[k] = v }
    }
    if (headers.isNotEmpty()) {
      MPVLib.setPropertyString(
        "http-header-fields",
        headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" },
      )
    }
  }

  /**
   * A user-supplied TV model, e.g. pushed with
   * `adb push tv.glb /sdcard/Android/data/<package>/files/tv.glb`. Not bundled, since downloaded
   * models usually can't be redistributed.
   */
  private fun customModel(): File? =
    getExternalFilesDir(null)?.let { File(it, MODEL_FILE_NAME) }?.takeIf { it.isFile }

  private fun loadCrtPose(): FloatArray? =
    poseStore.getString(KEY_CRT_POSE, null)
      ?.split(',')
      ?.mapNotNull { it.toFloatOrNull() }
      ?.takeIf { it.size == 8 }
      ?.toFloatArray()

  companion object {
    private const val TAG = "XrPlayerActivity"
    private const val POSE_PREFS = "xr_player"
    private const val KEY_CRT_POSE = "crt_pose"

    private const val MODEL_FILE_NAME = "tv.glb"

    // Width follows the TV's screen aspect; mpv letterboxes widescreen content inside it.
    private const val SURFACE_HEIGHT = 1080

    private const val SEEK_SECONDS = 10
    private const val LONG_SEEK_SECONDS = 300
    private const val VOLUME_STEP = 5
    private const val RENDER_THREAD_JOIN_MS = 3000L
  }
}
