package app.marlboroadvance.mpvex.ui.player.xr

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import app.marlboroadvance.mpvex.preferences.XrPreferences
import java.io.File

object XrSupport {
  /** Set on a player intent to skip the XR player, e.g. when OpenXR failed to start. */
  const val EXTRA_FORCE_2D = "app.marlboroadvance.mpvex.FORCE_2D"

  const val MODEL_FILE_NAME = "tv.glb"
  private const val POSE_PREFS = "xr_player"
  private const val KEY_CRT_POSE = "crt_pose"

  val isQuest: Boolean
    get() = Build.MANUFACTURER.equals("Oculus", ignoreCase = true) ||
      Build.MANUFACTURER.equals("Meta", ignoreCase = true)

  fun shouldUseXrPlayer(intent: Intent, preferences: XrPreferences): Boolean =
    isQuest && preferences.useXrPlayerOnQuest.get() && !intent.getBooleanExtra(EXTRA_FORCE_2D, false)

  /** Re-targets a player intent at [XrPlayerActivity], keeping its data and extras. */
  fun xrIntent(context: Context, source: Intent): Intent =
    Intent(source)
      .setClass(context, XrPlayerActivity::class.java)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  /**
   * Where a user-supplied TV model goes, e.g.
   * `adb push tv.glb /sdcard/Android/data/<package>/files/tv.glb`. Not bundled, since downloaded
   * models usually can't be redistributed.
   */
  fun customModelFile(context: Context): File? =
    context.getExternalFilesDir(null)?.let { File(it, MODEL_FILE_NAME) }

  fun hasCustomModel(context: Context): Boolean = customModelFile(context)?.isFile == true

  /** TV pose in stage space: px, py, pz, qx, qy, qz, qw, scale. */
  fun loadTvPose(context: Context): FloatArray? =
    context.getSharedPreferences(POSE_PREFS, Context.MODE_PRIVATE)
      .getString(KEY_CRT_POSE, null)
      ?.split(',')
      ?.mapNotNull { it.toFloatOrNull() }
      ?.takeIf { it.size == 8 }
      ?.toFloatArray()

  fun saveTvPose(context: Context, pose: FloatArray) {
    context.getSharedPreferences(POSE_PREFS, Context.MODE_PRIVATE)
      .edit { putString(KEY_CRT_POSE, pose.joinToString(",")) }
  }

  /** Forget where the TV was, so the next session places it in front of the viewer at default size. */
  fun resetTvPose(context: Context) {
    context.getSharedPreferences(POSE_PREFS, Context.MODE_PRIVATE).edit { remove(KEY_CRT_POSE) }
  }
}
