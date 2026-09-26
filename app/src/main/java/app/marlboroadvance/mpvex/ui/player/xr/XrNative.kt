package app.marlboroadvance.mpvex.ui.player.xr

import android.app.Activity

/**
 * Callbacks from the native OpenXR renderer. Every method is invoked on the render thread,
 * with the renderer's GL context current.
 */
interface XrBridge {
  /**
   * The external OES texture that mpv's output should be streamed into is ready.
   * [screenAspect] is the TV glass's width / height, so the video surface can match it.
   */
  fun onGlReady(texture: Int, screenAspect: Float)

  /** Latch the newest video frame, fill [matrix] with its texture transform, and report whether any frame has arrived. */
  fun updateVideoTexture(matrix: FloatArray): Boolean

  fun isPlaying(): Boolean

  /** One of [XrPlayerActivity.Action]'s codes. */
  fun onAction(code: Int)

  /** CRT pose in stage space: px, py, pz, qx, qy, qz, qw, scale. */
  fun onCrtPoseChanged(pose: FloatArray)

  /** The session is over; release anything tied to the GL context before it is destroyed. */
  fun onSessionEnded()

  /** Redraw the settings panel into [texture] (GL_TEXTURE_2D) if it changed. */
  fun updatePanel(texture: Int): Boolean

  /** A touch on the settings panel, 0..1 from its top left. Returns an [XrPanel] CMD_ code. */
  fun onPanelTouch(x: Float, y: Float, down: Boolean): Int

  /** The TV model changed, and with it the glass's width / height. */
  fun onScreenAspect(screenAspect: Float)
}

object XrNative {
  init {
    System.loadLibrary("mpvex_xr")
  }

  /**
   * Runs the OpenXR session until it ends. Blocks the calling thread, which becomes the render
   * thread. Returns false if OpenXR could not be initialised on this device.
   *
   * [modelPath] points at an optional glTF TV; the built-in set is used if it is missing or bad.
   * [settings] carries the VR preferences in the order XrPlayerActivity.nativeSettings() builds.
   */
  @JvmStatic
  external fun run(
    activity: Activity,
    bridge: XrBridge,
    initialPose: FloatArray?,
    modelPath: String?,
    settings: FloatArray,
  ): Boolean

  /** Asks the running session to exit; [run] returns shortly after. */
  @JvmStatic
  external fun requestExit()

  /** New settings, in the same order as [run] takes them; applied on the next frame. */
  @JvmStatic
  external fun setSettings(settings: FloatArray)

  /** Switches TV model; null for the built-in one. */
  @JvmStatic
  external fun setModel(modelPath: String?)
}
