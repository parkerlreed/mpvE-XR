package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

/** Settings for the immersive Quest CRT player. Read when the XR player starts. */
class XrPreferences(
  preferenceStore: PreferenceStore,
) {
  // Key predates this class (it used to live in PlayerPreferences), so existing choices carry over.
  val useXrPlayerOnQuest = preferenceStore.getBoolean("use_xr_player_on_quest", true)
  val useCustomModel = preferenceStore.getBoolean("xr_use_custom_model", true)

  val videoHeight = preferenceStore.getInt("xr_video_height", 1080)

  val scanlines = preferenceStore.getInt("xr_scanlines_percent", 35)
  val scanlinesFadeWithDistance = preferenceStore.getBoolean("xr_scanlines_fade", true)
  val glassReflections = preferenceStore.getInt("xr_glass_reflections_percent", 100)

  val roomDimming = preferenceStore.getInt("xr_room_dimming_percent", 0)
  val roomSaturation = preferenceStore.getInt("xr_room_saturation_percent", 100)

  val handOcclusionPaddingMm = preferenceStore.getInt("xr_hand_padding_mm", 4)
  val reachDistanceCm = preferenceStore.getInt("xr_reach_distance_cm", 60)

  val shortSeekSeconds = preferenceStore.getInt("xr_short_seek_seconds", 10)
  val longSeekSeconds = preferenceStore.getInt("xr_long_seek_seconds", 300)

  companion object {
    val VIDEO_HEIGHTS = listOf(720, 1080, 1440, 2160)
  }
}
