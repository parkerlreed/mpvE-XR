package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.MediaIdentifier
import app.marlboroadvance.mpvex.utils.storage.FileFilterUtils
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Playback history shared by the phone player ([PlayerActivity]) and the Quest XR player: how a
 * file is named and keyed, recently played, folder playlists, and (for the XR player, which has no
 * player view model) saving and restoring per-file state straight from mpv.
 *
 * Both players go through the same identifier code, so they read and write the same rows: stop in
 * one and pick up in the other.
 */
class PlaybackHistory(
  private val context: Context,
) : KoinComponent {
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  // ==================== Naming and identifying files ====================

  /** The media URI of a player intent (VIEW data, shared stream, or shared text). */
  fun uriFromIntent(intent: Intent): Uri? =
    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    } else {
      intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    }

  /** File name for a player intent: explicit title/filename extras first, then the URI. */
  fun fileName(intent: Intent): String {
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }
    val uri = uriFromIntent(intent) ?: return ""
    return fileName(uri)
  }

  /** Display name for a URI, from the content resolver if possible, else from the URI itself. */
  fun fileName(uri: Uri): String {
    displayName(uri)?.let { return it }
    return fileNameFromUriPath(uri)
  }

  /** DISPLAY_NAME from the content resolver, or null. */
  fun displayName(uri: Uri): String? =
    runCatching {
      context.contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Extracts a file name from a URI, handling URL encoding and network URLs.
   * For network streams, returns a temporary name that may be refined later from HTTP headers.
   */
  fun fileNameFromUriPath(uri: Uri): String {
    if (HttpUtils.isNetworkStream(uri)) {
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Unique identifier for this media's playback state and history.
   *
   * For local/offline files, uses fileName plus a hash of the file's stable full path so that two
   * files with the same name in different directories get distinct histories.
   * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from extras.
   * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string.
   */
  fun mediaIdentifier(intent: Intent, fileName: String): String {
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      val identifier = "network_${networkConnectionId}_${networkFilePath.hashCode()}"
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $networkFilePath)",
      )
      return identifier
    }

    val uri = uriFromIntent(intent)
    return if (uri != null && isRemote(uri)) {
      // For remote protocols: hash the URI so position is per-episode or per-stream.
      "${fileName}_${uri.toString().hashCode()}"
    } else if (uri != null) {
      localMediaIdentifier(uri, fileName)
    } else {
      fileName
    }
  }

  /** As [mediaIdentifier], for a playlist item URI. */
  fun mediaIdentifier(uri: Uri, fileName: String): String =
    if (isRemote(uri)) {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      localMediaIdentifier(uri, fileName)
    }

  private fun isRemote(uri: Uri): Boolean =
    uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" ||
      uri.scheme == "rtsp" || uri.scheme == "mms"

  /** Display name plus a hash of the file's full path, stable across launches. */
  private fun localMediaIdentifier(uri: Uri, fileName: String): String {
    val stablePath = stableLocalPath(uri)
    return if (stablePath.isNullOrBlank()) {
      // Fallback: keep the previous filename-only behavior if we can't resolve a path.
      fileName
    } else {
      // Delegate to the shared helper so deletion/rename cleanup keys match exactly.
      MediaIdentifier.forLocalPath(stablePath)
    }
  }

  /**
   * A stable, persistent path string for a local file URI, including its directory, or null.
   *
   * - file:// -> the URI path (already the full filesystem path)
   * - content:// -> the real filesystem path via MediaStore DATA, falling back to
   *   RELATIVE_PATH + DISPLAY_NAME, then the URI string itself.
   *
   * [Uri.resolveUri] is intentionally NOT used: it returns a temporary /proc/self/fd file
   * descriptor for content URIs, which changes every session and would not be a stable key.
   */
  fun stableLocalPath(uri: Uri): String? = runCatching {
    when (uri.scheme) {
      "file" -> uri.path
      "content" -> {
        context.contentResolver.query(
          uri,
          arrayOf(MediaStore.MediaColumns.DATA),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (columnIndex != -1) cursor.getString(columnIndex) else null
          } else {
            null
          }
        }?.takeIf { it.isNotBlank() } ?: relativeContentPath(uri) ?: uri.toString()
      }

      else -> uri.toString()
    }
  }.onFailure { e ->
    Log.e(TAG, "Error resolving stable local path for $uri", e)
  }.getOrNull()

  /** For content URIs without MediaStore DATA: RELATIVE_PATH + DISPLAY_NAME. */
  private fun relativeContentPath(uri: Uri): String? = runCatching {
    context.contentResolver.query(
      uri,
      arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val relative = if (relIdx != -1) cursor.getString(relIdx) else null
        val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
        if (!relative.isNullOrBlank() && !name.isNullOrBlank()) "$relative$name" else null
      } else {
        null
      }
    }
  }.getOrNull()

  /**
   * True only when the URI points at a LOCAL file that no longer exists on disk. Network streams,
   * content URIs we can't resolve to a path, and existing files all return false.
   */
  fun isLocalFileMissing(uri: Uri): Boolean {
    if (uri.scheme?.startsWith("http") == true ||
      uri.scheme == "rtmp" || uri.scheme == "rtsp" ||
      uri.scheme == "mms" || uri.scheme == "ftp" || uri.scheme == "ftps"
    ) {
      return false
    }

    val path = when (uri.scheme) {
      "file" -> uri.path
      "content" -> stableLocalPath(uri)?.takeIf { it.startsWith("/") }
      else -> uri.path?.takeIf { it.startsWith("/") }
    } ?: return false

    return runCatching { !File(path).exists() }.getOrDefault(false)
  }

  /** Path recorded in recently played / playlist history for a URI. */
  fun historyPath(uri: Uri): String =
    when (uri.scheme) {
      "file" -> uri.path ?: uri.toString()
      "content" -> {
        context.contentResolver
          .query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
              if (columnIndex != -1) cursor.getString(columnIndex) else null
            } else {
              null
            }
          } ?: uri.toString()
      }

      else -> uri.toString()
    }

  // ==================== Recently played ====================

  /** Records the file mpv currently has loaded, with its title, duration, size and resolution. */
  suspend fun addRecentlyPlayed(
    uri: Uri,
    fileName: String,
    launchSource: String?,
    playlistId: Int? = null,
  ) {
    runCatching {
      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }
      val filePath = historyPath(uri)

      val videoTitle = runCatching {
        MPVLib.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != fileName }

      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
        playlistId = playlistId,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
      if (playlistId != null) Log.d(TAG, "  - playlistId: $playlistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Folder playlists ====================

  /**
   * The videos next to [currentPath], in the order the browser would show them for [launchSource],
   * and the current file's index. Null when there's nothing to make a playlist from.
   */
  suspend fun folderPlaylist(currentPath: String, launchSource: String): Pair<List<Uri>, Int>? {
    val currentFile = File(currentPath)
    if (!currentFile.exists()) return null

    val parentFolder = currentFile.parentFile ?: return null

    val files = parentFolder.listFiles { file ->
      file.isFile &&
        FileTypeUtils.isVideoFile(file) &&
        !FileFilterUtils.shouldSkipFile(file)
    } ?: return null

    val siblingFiles = if (launchSource == "video_list" || launchSource == "recently_played_button" || launchSource == "first_video_button") {
      val videoSortType = browserPreferences.videoSortType.get()
      val videoSortOrder = browserPreferences.videoSortOrder.get()
      val bucketId = parentFolder.absolutePath.replace("\\", "/")
      val videosInFolder =
        app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(
          context,
          setOf(bucketId),
        )
      val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(videosInFolder, videoSortType, videoSortOrder)
      sortedVideos.mapNotNull { video -> files.find { it.absolutePath == video.path } }
    } else {
      files.sortedWith { f1, f2 -> app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name) }
    }

    if (siblingFiles.size <= 1) return null

    val index = siblingFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }
    if (index == -1) return null
    return siblingFiles.map { it.toUri() } to index
  }

  // ==================== Saving and restoring (XR player) ====================

  /**
   * What the XR player saves, read from mpv on the calling thread so it can be taken just before
   * mpv shuts down. Pass it to [save].
   */
  class Snapshot internal constructor(
    val position: Int,
    val duration: Int,
    val speed: Double,
    val sid: Int,
    val secondarySid: Int,
    val aid: Int,
    val subDelay: Double,
    val subSpeed: Double,
    val audioDelay: Double,
  )

  fun snapshot(): Snapshot {
    fun track(name: String) = MPVLib.getPropertyString(name)?.toIntOrNull() ?: -1
    return Snapshot(
      position = MPVLib.getPropertyInt("time-pos") ?: 0,
      duration = MPVLib.getPropertyInt("duration") ?: 0,
      speed = MPVLib.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED,
      sid = track("sid"),
      secondarySid = track("secondary-sid"),
      aid = track("aid"),
      subDelay = MPVLib.getPropertyDouble("sub-delay") ?: 0.0,
      subSpeed = MPVLib.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED,
      audioDelay = MPVLib.getPropertyDouble("audio-delay") ?: 0.0,
    )
  }

  /**
   * Saves a snapshot the same way [PlayerActivity] does. Settings the XR player has no controls
   * for (video zoom, external subtitles) are kept from the existing row rather than cleared.
   */
  suspend fun save(identifier: String, s: Snapshot) {
    if (identifier.isBlank()) return
    runCatching {
      val oldState = playbackStateRepository.getVideoDataByTitle(identifier)

      val lastPosition = if (!playerPreferences.savePositionOnQuit.get()) {
        oldState?.lastPosition ?: 0
      } else if (s.position < s.duration - 1) {
        s.position
      } else {
        0
      }
      val timeRemaining = if (s.duration > lastPosition) s.duration - lastPosition else 0

      val (effectiveSid, effectiveSecondarySid) = if (s.sid <= 0 && s.secondarySid > 0) {
        s.secondarySid to -1
      } else {
        s.sid to s.secondarySid
      }

      val watchedThreshold = browserPreferences.watchedThreshold.get() / 100f
      val duration = s.duration.toFloat()
      val isFinished = duration > 0 && s.position >= duration - 1
      val progress = if (duration > 0) s.position / duration else 0f
      val savedProgress = if (duration > 0) lastPosition / duration else 0f

      playbackStateRepository.upsert(
        PlaybackStateEntity(
          mediaTitle = identifier,
          lastPosition = lastPosition,
          playbackSpeed = s.speed,
          videoZoom = oldState?.videoZoom ?: 0f,
          sid = effectiveSid,
          secondarySid = effectiveSecondarySid,
          subDelay = (s.subDelay * MILLISECONDS_TO_SECONDS).toInt(),
          subSpeed = s.subSpeed,
          aid = s.aid,
          audioDelay = (s.audioDelay * MILLISECONDS_TO_SECONDS).toInt(),
          timeRemaining = timeRemaining,
          externalSubtitles = oldState?.externalSubtitles ?: "",
          hasBeenWatched = progress >= watchedThreshold || isFinished ||
            savedProgress >= watchedThreshold || oldState?.hasBeenWatched == true,
        ),
      )
      Log.d(TAG, "Saved playback state for $identifier at ${lastPosition}s")
    }.onFailure { e ->
      Log.e(TAG, "Error saving playback state", e)
    }
  }

  /**
   * Restores saved tracks, delays, speed and (if "Save position on quit" is on) the position for
   * the file mpv just loaded. Returns whether a saved state existed, for [TrackSelector].
   */
  suspend fun restore(identifier: String): Boolean {
    if (identifier.isBlank()) return false
    return runCatching {
      val state = playbackStateRepository.getVideoDataByTitle(identifier)
      if (state == null) {
        MPVLib.setPropertyDouble("sub-speed", subtitlesPreferences.defaultSubSpeed.get().toDouble())
        return@runCatching false
      }

      if (state.sid > 0) {
        MPVLib.setPropertyInt("sid", state.sid)
        if (state.secondarySid > 0 && state.secondarySid != state.sid) {
          MPVLib.setPropertyInt("secondary-sid", state.secondarySid)
        } else {
          MPVLib.setPropertyString("secondary-sid", "no")
        }
      } else if (state.secondarySid > 0) {
        // A single subtitle must stay at the bottom, so promote it to primary.
        MPVLib.setPropertyInt("sid", state.secondarySid)
        MPVLib.setPropertyString("secondary-sid", "no")
      }
      if (state.aid > 0) MPVLib.setPropertyInt("aid", state.aid)

      MPVLib.setPropertyDouble("sub-delay", state.subDelay / DELAY_DIVISOR)
      MPVLib.setPropertyDouble("speed", state.playbackSpeed)
      MPVLib.setPropertyDouble("audio-delay", state.audioDelay / DELAY_DIVISOR)
      MPVLib.setPropertyDouble("sub-speed", state.subSpeed)

      if (playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0) {
        MPVLib.setPropertyInt("time-pos", state.lastPosition)
        Log.d(TAG, "Resumed $identifier at ${state.lastPosition}s")
      }
      true
    }.onFailure { e ->
      Log.e(TAG, "Error restoring playback state", e)
    }.getOrDefault(false)
  }

  companion object {
    private const val TAG = "PlaybackHistory"
    private const val MILLISECONDS_TO_SECONDS = 1000
    private const val DELAY_DIVISOR = 1000.0
    private const val DEFAULT_PLAYBACK_SPEED = 1.0
    private const val DEFAULT_SUB_SPEED = 1.0
  }
}
