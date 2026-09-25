package app.marlboroadvance.mpvex.ui.preferences

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.XrPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference as StoredPreference
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.player.xr.XrSupport
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Settings for the immersive Quest CRT player. Only reachable on Quest. */
@Serializable
object VrPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val preferences = koinInject<XrPreferences>()
    val hasCustomModel = remember { XrSupport.hasCustomModel(context) }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(id = R.string.pref_vr),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_player)) }
          item {
            PreferenceCard {
              val useXrPlayer by preferences.useXrPlayerOnQuest.collectAsState()
              SwitchPreference(
                value = useXrPlayer,
                onValueChange = preferences.useXrPlayerOnQuest::set,
                title = { Text(stringResource(R.string.pref_player_xr_player)) },
                summary = { Summary(stringResource(R.string.pref_player_xr_player_summary)) },
              )

              PreferenceDivider()

              val videoHeight by preferences.videoHeight.collectAsState()
              ListPreference(
                value = videoHeight,
                onValueChange = preferences.videoHeight::set,
                values = XrPreferences.VIDEO_HEIGHTS,
                valueToText = { AnnotatedString("${it}p") },
                title = { Text(stringResource(R.string.pref_vr_video_resolution)) },
                summary = { Summary(stringResource(R.string.pref_vr_video_resolution_summary, videoHeight)) },
              )
            }
          }

          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_tv)) }
          item {
            PreferenceCard {
              val useCustomModel by preferences.useCustomModel.collectAsState()
              SwitchPreference(
                value = useCustomModel && hasCustomModel,
                onValueChange = preferences.useCustomModel::set,
                enabled = hasCustomModel,
                title = { Text(stringResource(R.string.pref_vr_custom_model)) },
                summary = {
                  Summary(
                    stringResource(
                      if (hasCustomModel) R.string.pref_vr_custom_model_summary else R.string.pref_vr_custom_model_missing,
                      XrSupport.MODEL_FILE_NAME,
                    ),
                  )
                },
              )

              PreferenceDivider()

              Preference(
                title = { Text(stringResource(R.string.pref_vr_reset_position)) },
                summary = { Summary(stringResource(R.string.pref_vr_reset_position_summary)) },
                onClick = {
                  XrSupport.resetTvPose(context)
                  Toast.makeText(context, R.string.pref_vr_reset_position_done, Toast.LENGTH_SHORT).show()
                },
              )
            }
          }

          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_picture)) }
          item {
            PreferenceCard {
              PercentSlider(preferences.scanlines, R.string.pref_vr_scanlines, 0..100)

              PreferenceDivider()

              val fade by preferences.scanlinesFadeWithDistance.collectAsState()
              SwitchPreference(
                value = fade,
                onValueChange = preferences.scanlinesFadeWithDistance::set,
                title = { Text(stringResource(R.string.pref_vr_scanlines_fade)) },
                summary = { Summary(stringResource(R.string.pref_vr_scanlines_fade_summary)) },
              )

              PreferenceDivider()

              PercentSlider(preferences.glassReflections, R.string.pref_vr_glass_reflections, 0..100)
            }
          }

          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_room)) }
          item {
            PreferenceCard {
              PercentSlider(preferences.roomDimming, R.string.pref_vr_room_dimming, 0..95)

              PreferenceDivider()

              PercentSlider(preferences.roomSaturation, R.string.pref_vr_room_saturation, 0..100)
            }
          }

          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_hands)) }
          item {
            PreferenceCard {
              IntSlider(
                preferences.handOcclusionPaddingMm,
                R.string.pref_vr_hand_padding,
                R.string.pref_vr_hand_padding_summary,
                0..15,
              )

              PreferenceDivider()

              IntSlider(
                preferences.reachDistanceCm,
                R.string.pref_vr_reach_distance,
                R.string.pref_vr_reach_distance_summary,
                40..100,
                step = 5,
              )
            }
          }

          item { PreferenceSectionHeader(title = stringResource(R.string.pref_vr_section_seeking)) }
          item {
            PreferenceCard {
              IntSlider(
                preferences.shortSeekSeconds,
                R.string.pref_vr_short_seek,
                R.string.pref_vr_seconds_summary,
                5..60,
                step = 5,
              )

              PreferenceDivider()

              IntSlider(
                preferences.longSeekSeconds,
                R.string.pref_vr_long_seek,
                R.string.pref_vr_seconds_summary,
                60..900,
                step = 30,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Summary(text: String) {
  Text(text, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun PercentSlider(preference: StoredPreference<Int>, titleRes: Int, range: IntRange) {
  IntSlider(preference, titleRes, R.string.pref_vr_percent_summary, range)
}

/** Integer slider whose summary string takes the current value as its only argument. */
@Composable
private fun IntSlider(
  preference: StoredPreference<Int>,
  titleRes: Int,
  summaryRes: Int,
  range: IntRange,
  step: Int = 1,
) {
  val value by preference.collectAsState()
  val snap = { v: Float ->
    (((v.roundToInt() - range.first) / step.toFloat()).roundToInt() * step + range.first)
      .coerceIn(range.first, range.last)
  }
  SliderPreference(
    value = value.toFloat(),
    onValueChange = { preference.set(snap(it)) },
    sliderValue = value.toFloat(),
    onSliderValueChange = { preference.set(snap(it)) },
    title = { Text(stringResource(titleRes)) },
    valueRange = range.first.toFloat()..range.last.toFloat(),
    valueSteps = (range.last - range.first) / step - 1,
    summary = { Summary(stringResource(summaryRes, value)) },
  )
}
