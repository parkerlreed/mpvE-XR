// Player action codes sent to Kotlin. Mirrors XrPlayerActivity.Action.
#pragma once

enum ActionCode : int {
  kTogglePause = 1,
  kSeekBack = 2,
  kSeekForward = 3,
  kVolumeUp = 4,
  kVolumeDown = 5,
  kShowProgress = 6,
  kCycleSubtitles = 7,
  kCycleAudio = 8,
  kExit = 9,
  kPause = 10,
  kSeekBackLong = 11,
  kSeekForwardLong = 12,
  kStop = 13,
};

// What the settings panel asks of the renderer after a touch. Mirrors XrPanel.
enum PanelCommand : int {
  kPanelNone = 0,
  kPanelClose = 1,
  kPanelRecenter = 2,
  kPanelSizeFirst = 16,  // + index into the TV size presets
};
