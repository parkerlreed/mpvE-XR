package app.marlboroadvance.mpvex.ui.player.xr

import android.content.Context
import android.content.Intent
import android.os.Build
import app.marlboroadvance.mpvex.preferences.PlayerPreferences

object XrSupport {
  /** Set on a player intent to skip the XR player, e.g. when OpenXR failed to start. */
  const val EXTRA_FORCE_2D = "app.marlboroadvance.mpvex.FORCE_2D"

  val isQuest: Boolean
    get() = Build.MANUFACTURER.equals("Oculus", ignoreCase = true) ||
      Build.MANUFACTURER.equals("Meta", ignoreCase = true)

  fun shouldUseXrPlayer(intent: Intent, preferences: PlayerPreferences): Boolean =
    isQuest && preferences.useXrPlayerOnQuest.get() && !intent.getBooleanExtra(EXTRA_FORCE_2D, false)

  /** Re-targets a player intent at [XrPlayerActivity], keeping its data and extras. */
  fun xrIntent(context: Context, source: Intent): Intent =
    Intent(source)
      .setClass(context, XrPlayerActivity::class.java)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
