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
   */
  @JvmStatic
  external fun run(activity: Activity, bridge: XrBridge, initialPose: FloatArray?, modelPath: String?): Boolean

  /** Asks the running session to exit; [run] returns shortly after. */
  @JvmStatic
  external fun requestExit()
}
