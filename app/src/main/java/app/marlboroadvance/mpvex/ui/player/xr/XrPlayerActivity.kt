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
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.PlaylistRepository
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.XrPreferences
import app.marlboroadvance.mpvex.ui.player.MPVView
import app.marlboroadvance.mpvex.ui.player.PlaybackHistory
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.TrackSelector
import app.marlboroadvance.mpvex.ui.player.openContentFd
import app.marlboroadvance.mpvex.ui.player.resolveUri
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
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
  private val xrPreferences: XrPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val playlistRepository: PlaylistRepository by inject()
  private val history by lazy { PlaybackHistory(this) }
  private val trackSelector by lazy { TrackSelector(audioPreferences, subtitlesPreferences) }

  // What's playing, for resume and history; moves along as the playlist advances.
  private var fileName = ""
  private var mediaIdentifier = ""
  private var currentUri: Uri? = null
  private var onPlaylistItem = false  // false while still on the file the intent opened
  private var fileLoaded = false      // don't save over a file's state before it has loaded
  private var playlist: List<Uri> = emptyList()
  private var playlistIndex = 0
  private var playlistId: Int? = null

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
    // Same guard as PlayerActivity: a local file deleted since it was listed.
    if (history.uriFromIntent(intent)?.let(history::isLocalFileMissing) == true) {
      Toast.makeText(this, R.string.toast_file_no_longer_exists, Toast.LENGTH_SHORT).show()
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
    setUpFromIntent(intent)
    pendingPath = path

    renderThread = thread(name = "mpvEx-XR") {
      val model = XrSupport.customModelFile(this)
        ?.takeIf { xrPreferences.useCustomModel.get() && it.isFile }
      val ok = XrNative.run(this, bridge, XrSupport.loadTvPose(this), model?.path, nativeSettings())
      if (!ok) runOnUiThread { fallBackTo2d() }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val path = playablePath(intent) ?: return
    saveState()
    setIntent(intent)
    applyHttpHeaders(intent)
    setUpFromIntent(intent)
    MPVLib.command("loadfile", path)
  }

  override fun onStop() {
    if (mpvInitialized) {
      MPVLib.setPropertyBoolean("pause", true)
      saveState()
    }
    super.onStop()
  }

  override fun onDestroy() {
    XrNative.requestExit()
    renderThread?.join(RENDER_THREAD_JOIN_MS)
    renderThread = null
    // Must finish before mpv goes away, and lifecycleScope is already cancelled here.
    saveState(blocking = true)
    destroyMpv()
    super.onDestroy()
  }

  /** Name, history key and playlist for the file an intent opens. */
  private fun setUpFromIntent(intent: Intent) {
    currentUri = history.uriFromIntent(intent)
    fileName = history.fileName(intent).ifBlank { intent.data?.lastPathSegment ?: "Unknown Video" }
    mediaIdentifier = history.mediaIdentifier(intent, fileName)
    onPlaylistItem = false
    fileLoaded = false

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)
    playlist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }
    // Same sources as PlayerActivity: a saved playlist, or the file's folder in playlist mode.
    val id = playlistId
    if (playlist.isEmpty() && id != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val items = runCatching { playlistRepository.getPlaylistItemsAsUris(id) }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) { playlist = items }
      }
    } else if (playlist.isEmpty() && playerPreferences.playlistMode.get()) {
      val path = playablePath(intent) ?: return
      val launchSource = intent.getStringExtra("launch_source") ?: ""
      lifecycleScope.launch(Dispatchers.IO) {
        val folder = runCatching { history.folderPlaylist(path, launchSource) }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) {
          playlist = folder.first
          playlistIndex = folder.second
        }
      }
    }
  }

  /** Saves position, tracks, delays and speed for the current file, like the phone player. */
  private fun saveState(blocking: Boolean = false) {
    if (!mpvInitialized || !fileLoaded || mediaIdentifier.isBlank()) return
    val snapshot = runCatching { history.snapshot() }.getOrNull() ?: return
    val identifier = mediaIdentifier
    if (blocking) {
      runBlocking(Dispatchers.IO) { history.save(identifier, snapshot) }
    } else {
      lifecycleScope.launch(Dispatchers.IO) { history.save(identifier, snapshot) }
    }
  }

  /** Autoplay through the playlist, or close at the end, following the player settings. */
  private fun onEndOfFile() {
    val next = playlistIndex + 1
    if (playlist.isNotEmpty() && next < playlist.size && playerPreferences.autoplayNextVideo.get()) {
      playItem(next)
    } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
      finish()
    } else {
      saveState()
    }
  }

  private fun playItem(index: Int) {
    if (index !in playlist.indices) return
    saveState()
    fileLoaded = false
    val uri = playlist[index]
    if (history.isLocalFileMissing(uri)) {
      Log.w(TAG, "Skipping missing playlist item at index $index: $uri")
      Toast.makeText(this, R.string.toast_file_no_longer_exists, Toast.LENGTH_SHORT).show()
      if (index + 1 < playlist.size) {
        playItem(index + 1)
      } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
        finish()
      }
      return
    }

    playlistIndex = index
    currentUri = uri
    onPlaylistItem = true
    fileName = history.fileName(uri)
    mediaIdentifier = history.mediaIdentifier(uri, fileName)
    if (HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let {
        MPVLib.setPropertyString("http-header-fields", "Referer: ${it.replace(",", "\\,")}")
      }
    }
    playlistId?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        runCatching { playlistRepository.updatePlayHistory(id, history.historyPath(uri)) }
          .onFailure { Log.e(TAG, "Error updating playlist history", it) }
      }
    }
    MPVLib.command("loadfile", uri.openContentFd(this) ?: uri.toString())
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
      val height = xrPreferences.videoHeight.get().takeIf { it in XrPreferences.VIDEO_HEIGHTS } ?: 1080
      val width = ((height * screenAspect).toInt() / 2 * 2).coerceIn(height, height * 2)
      val st = SurfaceTexture(texture).apply {
        setDefaultBufferSize(width, height)
        setOnFrameAvailableListener({ frameAvailable.set(true) }, Handler(Looper.getMainLooper()))
      }
      val s = Surface(st)
      surfaceTexture = st
      surface = s

      // Equivalent of BaseMPVView.surfaceCreated, with our texture-backed surface.
      MPVLib.attachSurface(s)
      MPVLib.setOptionString("force-window", "yes")
      MPVLib.setPropertyString("android-surface-size", "${width}x$height")
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
      XrSupport.saveTvPose(this@XrPlayerActivity, pose)
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
      Action.SEEK_BACK -> seek(-xrPreferences.shortSeekSeconds.get())
      Action.SEEK_FORWARD -> seek(xrPreferences.shortSeekSeconds.get())
      Action.SEEK_BACK_LONG -> seek(-xrPreferences.longSeekSeconds.get())
      Action.SEEK_FORWARD_LONG -> seek(xrPreferences.longSeekSeconds.get())
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
        "eof-reached" -> if (value) runOnUiThread { onEndOfFile() }
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
    fileLoaded = true
    val identifier = mediaIdentifier
    val name = fileName
    val current = intent
    val item = if (onPlaylistItem) currentUri else null

    // Resume where this file was left (in either player), then pick default tracks if it's new.
    lifecycleScope.launch(Dispatchers.IO) {
      val hasState = history.restore(identifier)
      trackSelector.onFileLoaded(hasState)
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val playlistItem = playlist.getOrNull(playlistIndex)
      if (playlist.isNotEmpty() && playlistItem != null) {
        history.addRecentlyPlayed(playlistItem, name, "playlist", playlistId)
      } else {
        val launchSource = when {
          current.getStringExtra("launch_source") != null -> current.getStringExtra("launch_source")
          current.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }
        currentUri?.let { history.addRecentlyPlayed(it, name, launchSource) }
      }
    }

    if (!subtitlesPreferences.autoloadMatchingSubtitles.get()) return
    lifecycleScope.launch {
      // Mirrors PlayerActivity: network files look for subtitles next to the original remote path.
      val networkPath = current.getStringExtra("network_file_path")
      val connectionId = current.getLongExtra("network_connection_id", -1L)
      if (item != null) {
        SubtitleOps.autoloadSubtitles(history.historyPath(item), name)
      } else if (networkPath != null && connectionId != -1L) {
        SubtitleOps.autoloadSubtitles(networkPath, name, connectionId)
      } else {
        playablePath(current)?.let { SubtitleOps.autoloadSubtitles(it, name) }
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

  /** Mirrors XrSettings in xr_app.cpp; order matters. */
  private fun nativeSettings(): FloatArray =
    floatArrayOf(
      xrPreferences.handOcclusionPaddingMm.get() / 1000f,
      1f - xrPreferences.roomDimming.get().coerceIn(0, 100) / 100f,
      xrPreferences.roomSaturation.get().coerceIn(0, 100) / 100f,
      xrPreferences.scanlines.get().coerceIn(0, 100) / 100f,
      if (xrPreferences.scanlinesFadeWithDistance.get()) 1f else 0f,
      xrPreferences.glassReflections.get().coerceIn(0, 100) / 100f,
      xrPreferences.reachDistanceCm.get() / 100f,
    )

  companion object {
    private const val TAG = "XrPlayerActivity"
    private const val VOLUME_STEP = 5
    private const val RENDER_THREAD_JOIN_MS = 3000L
  }
}
