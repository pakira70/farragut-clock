package com.example.farragutclock

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

internal data class ClockSessionToken(val id: Long)
internal enum class ClockSessionEndReason { NONE, EXTERNAL, HOST_DETACHED, POWER_DISCONNECTED, SCREEN_OFF, EXIT_HOME }

/** Ownership is independent of Android so handoff/departure rules can be checked locally. */
internal class ClockSessionOwnership {
  var active: Boolean = true
    private set
  var owner: Any? = null
    private set
  var handingOff: Boolean = false
    private set
  var originOwner: Any? = null
    private set

  fun attach(owner: Any): Boolean {
    if (!active) return false
    this.owner = owner
    return true
  }

  fun beginHandoff(): Boolean {
    if (!active || owner == null || handingOff) return false
    originOwner = owner
    handingOff = true
    return true
  }

  fun mayAttachDream(clockActivityOwned: Boolean): Boolean = active && !handingOff && !clockActivityOwned

  /** True means the visible clock really departed and its session should end. */
  fun detach(owner: Any): Boolean {
    if (originOwner === owner) originOwner = null
    if (!active || this.owner !== owner) return false
    this.owner = null
    return !handingOff
  }

  fun finishHandoff(owner: Any): Boolean {
    if (!attach(owner)) return false
    if (originOwner === owner) originOwner = null
    handingOff = false
    return true
  }

  fun close() {
    active = false
    owner = null
    handingOff = false
    originOwner = null
  }
}

/** One live scene/data scope, transferred between the system dream, YTM overlay, and clock Activity. */
internal object ClockSession {
  private var nextId = 0L
  private var current: Session? = null
  private var lastEndReason = ClockSessionEndReason.NONE
  private val mainHandler = Handler(Looper.getMainLooper())

  private class Host(
    val owner: Any,
    val context: Context,
    val container: FrameLayout,
    val applyBrightness: (Float) -> Unit,
    val beginMicrophone: () -> Boolean = { true },
    val endMicrophone: () -> Unit = {},
    val finish: () -> Unit = {},
  )

  private class Session(val context: Context, val token: ClockSessionToken) {
    val ownership = ClockSessionOwnership()
    lateinit var controller: ClockSceneController
    var host: Host? = null
    var originHost: Host? = null
    var onClockReady: (() -> Unit)? = null
    var awaitingActivityDraw = false
    var awakeBootstrapUntilMillis = 0L
    var screenOffCheck: Runnable? = null
    lateinit var receiver: BroadcastReceiver
  }

  fun currentToken(): ClockSessionToken? = current?.token

  fun isActive(token: ClockSessionToken): Boolean =
    current?.let { it.token == token && it.ownership.active } == true

  /** Safe to pass cover touches through only after the clock, rather than YTM, owns the scene. */
  fun isClockInteractive(token: ClockSessionToken): Boolean {
    val session = matching(token) ?: return false
    val activity = session.host?.owner as? ClockActivity ?: return false
    return session.ownership.owner === activity && !session.ownership.handingOff &&
      activity.isClockInteractiveHost()
  }

  fun needsAwakeBootstrap(token: ClockSessionToken): Boolean {
    val session = matching(token) ?: return true
    val activity = (session.originHost?.owner ?: session.host?.owner) as? ClockActivity
    return activity?.isAwakeClockHost() != true
  }

  /** A temporary cover of this app's own scene; the caller owns and recycles the returned bitmap. */
  fun snapshotRecognitionFrame(token: ClockSessionToken): Bitmap? {
    val view = matching(token)?.controller?.view ?: return null
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    return try {
      view.draw(Canvas(bitmap))
      bitmap
    } catch (_: RuntimeException) {
      bitmap.recycle()
      null
    }
  }

  fun beginRecognitionHandoff(token: ClockSessionToken): Boolean {
    val session = matching(token) ?: return false
    if (!session.ownership.beginHandoff()) return false
    session.originHost = session.host
    trace("begin handoff", session)
    return true
  }

  /** The opaque overlay must already be attached before dismissing the always-on-top Dream. */
  fun dismissDreamForRecognition(token: ClockSessionToken): Boolean {
    val session = matching(token) ?: return false
    if (!session.ownership.handingOff || session.host?.owner !is FrameLayout ||
      session.host?.container?.isAttachedToWindow != true) return false
    dismissOriginatingDream(session)
    return true
  }

  fun attachDream(
    context: Context,
    owner: Any,
    container: FrameLayout,
    applyBrightness: (Float) -> Unit,
    beginMicrophone: () -> Boolean,
    endMicrophone: () -> Unit,
    finish: () -> Unit,
  ): ClockSessionToken {
    val session = current ?: create(context)
    if (!session.ownership.mayAttachDream(session.host?.owner is ClockActivity)) {
      // Android may start another idle dream while YTM is listening or the clock Activity is visible.
      // It must not take the shared scene or finish the host we are deliberately keeping visible.
      trace("reject incoming Dream ${ownerLabel(owner)}", session)
      finish()
      return session.token
    }
    trace("attach Dream ${ownerLabel(owner)}", session)
    attachHost(session, Host(owner, context, container, applyBrightness, beginMicrophone, endMicrophone, finish))
    return session.token
  }

  fun detachDream(token: ClockSessionToken, owner: Any) = detachHost(token, owner)

  fun attachRecognitionOverlay(
    token: ClockSessionToken,
    container: FrameLayout,
    applyBrightness: (Float) -> Unit,
  ): Boolean {
    val session = matching(token) ?: return false
    if (!session.ownership.handingOff) return false
    trace("attach overlay", session)
    attachHost(session, Host(container, container.context, container, applyBrightness))
    return true
  }

  fun returnToClock(
    token: ClockSessionToken,
    context: Context,
    onClockReady: () -> Unit,
  ): Boolean = launchClockActivity(token, context, onClockReady, awakeBootstrap = false)

  /** Establish an awake Activity before moving the scene back into the existing YTM overlay. */
  fun bootstrapAwakeClock(
    token: ClockSessionToken,
    context: Context,
    onClockReady: () -> Unit,
  ): Boolean = launchClockActivity(token, context, onClockReady, awakeBootstrap = true)

  private fun launchClockActivity(
    token: ClockSessionToken,
    context: Context,
    onClockReady: () -> Unit,
    awakeBootstrap: Boolean,
  ): Boolean {
    val session = matching(token) ?: return false
    if (!session.ownership.handingOff) {
      val activityHost = session.host?.takeIf { it.owner is ClockActivity } ?: return false
      session.onClockReady = onClockReady
      (activityHost.owner as ClockActivity).requestReadyClockNotification(holdSystemBars = !awakeBootstrap)
      return true
    }
    session.onClockReady = onClockReady
    session.awaitingActivityDraw = true
    val hasOriginatingDream = session.originHost?.owner is FarragutDreamService
    session.awakeBootstrapUntilMillis =
      if (awakeBootstrap || hasOriginatingDream) SystemClock.uptimeMillis() + 3_000L else 0L
    trace("request clock Activity", session)
    return try {
      context.startActivity(Intent(context, ClockActivity::class.java)
        .putExtra(ClockActivity.SESSION_ID_EXTRA, token.id)
        .putExtra(ClockActivity.HOLD_SYSTEM_BARS_EXTRA, !awakeBootstrap)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
          Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
      // The server now knows the Activity's manifest turnScreenOn flag. Dismiss the topmost Dream
      // only after requesting that awake host, so it cannot put the device to sleep in between.
      dismissOriginatingDream(session)
      true
    } catch (_: RuntimeException) {
      session.onClockReady = null
      session.awaitingActivityDraw = false
      session.awakeBootstrapUntilMillis = 0L
      false
    }
  }

  fun attachActivity(
    token: ClockSessionToken,
    activity: ClockActivity,
    container: FrameLayout,
    applyBrightness: (Float) -> Unit,
  ): Boolean {
    val session = matching(token) ?: run {
      Log.d("ClockSession", "reject Activity for inactive session=${token.id} lastEnd=$lastEndReason")
      return false
    }
    trace("attach Activity ${ownerLabel(activity)}", session)
    attachHost(session, Host(activity, activity, container, applyBrightness, finish = activity::finishAndRemoveTask))
    session.ownership.finishHandoff(activity)
    return true
  }

  fun activityDrawn(token: ClockSessionToken, activity: ClockActivity) {
    val session = matching(token) ?: return
    if (session.ownership.owner !== activity) return
    trace("Activity drawn", session)
    session.awaitingActivityDraw = false
    session.awakeBootstrapUntilMillis = 0L
    val callback = session.onClockReady
    session.onClockReady = null
    callback?.invoke()
  }

  fun detachActivity(token: ClockSessionToken, activity: ClockActivity, destroyed: Boolean = false) {
    val session = matching(token) ?: return
    if (!destroyed && session.ownership.owner === activity && session.awaitingActivityDraw &&
      SystemClock.uptimeMillis() < session.awakeBootstrapUntilMillis) {
      trace("defer Activity stop during awake bootstrap", session)
      return
    }
    detachHost(token, activity)
  }

  fun configurationChanged(token: ClockSessionToken, configuration: android.content.res.Configuration) {
    matching(token)?.controller?.onConfigurationChanged(configuration)
  }

  fun end(token: ClockSessionToken, reason: ClockSessionEndReason = ClockSessionEndReason.EXTERNAL) {
    val session = matching(token) ?: return
    val host = session.host
    val origin = session.originHost?.takeIf {
      it.owner === session.ownership.originOwner && it.owner !== host?.owner
    }
    lastEndReason = reason
    trace("end reason=$reason", session)
    // Invalidate first: cancelling the recognition scope must never bring the clock back to the foreground.
    current = null
    session.ownership.close()
    session.screenOffCheck?.let(mainHandler::removeCallbacks)
    session.screenOffCheck = null
    session.onClockReady = null
    session.awaitingActivityDraw = false
    session.awakeBootstrapUntilMillis = 0L
    session.context.unregisterReceiver(session.receiver)
    session.host?.endMicrophone?.invoke()
    session.controller.stop()
    (session.controller.view.parent as? ViewGroup)?.removeView(session.controller.view)
    session.host = null
    session.originHost = null
    host?.finish?.invoke()
    origin?.finish?.invoke()
  }

  private fun detachHost(token: ClockSessionToken, owner: Any) {
    val session = matching(token) ?: return
    trace("detach ${ownerLabel(owner)}", session)
    if (session.originHost?.owner === owner) session.originHost = null
    if (session.ownership.detach(owner)) end(token, ClockSessionEndReason.HOST_DETACHED)
  }

  private fun matching(token: ClockSessionToken): Session? =
    current?.takeIf { it.token == token && it.ownership.active }

  private fun dismissOriginatingDream(session: Session) {
    session.originHost?.takeIf {
      it.owner === session.ownership.originOwner && it.owner is FarragutDreamService
    }?.finish?.invoke()
  }

  private fun attachHost(session: Session, host: Host) {
    session.host?.takeIf { it.owner !== host.owner }?.endMicrophone?.invoke()
    val view = session.controller.view
    (view.parent as? ViewGroup)?.removeView(view)
    session.host = host
    session.ownership.attach(host.owner)
    host.container.keepScreenOn = true
    host.container.addView(view, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    session.controller.start()
    session.controller.refreshHost()
  }

  private fun create(context: Context): Session {
    val session = Session(context.applicationContext, ClockSessionToken(++nextId))
    session.controller = ClockSceneController(
      session.context,
      applyHostBrightness = { session.host?.applyBrightness?.invoke(it) },
      onExitClock = { exitToHome(session.token) },
      beginMicrophoneSession = { session.host?.beginMicrophone?.invoke() == true },
      endMicrophoneSession = { session.host?.endMicrophone?.invoke() },
    )
    session.receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          Intent.ACTION_POWER_DISCONNECTED -> end(session.token, ClockSessionEndReason.POWER_DISCONNECTED)
          Intent.ACTION_SCREEN_OFF -> checkPhysicalScreenOff(session)
        }
      }
    }
    ContextCompat.registerReceiver(session.context, session.receiver, IntentFilter().apply {
      addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(Intent.ACTION_SCREEN_OFF)
    }, ContextCompat.RECEIVER_NOT_EXPORTED)
    current = session
    return session
  }

  fun exitToHome(token: ClockSessionToken) {
    val session = matching(token) ?: return
    try {
      session.host?.context?.startActivity(Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } finally {
      end(token, ClockSessionEndReason.EXIT_HOME)
    }
  }

  fun debugSummary(): String {
    val session = current ?: return "session=none lastEnd=$lastEndReason"
    val view = session.controller.view
    return "session=${session.token.id} active=${session.ownership.active}" +
      " owner=${session.ownership.owner?.javaClass?.simpleName}" +
      " origin=${session.ownership.originOwner?.javaClass?.simpleName}" +
      " handingOff=${session.ownership.handingOff}" +
      " awaitingActivityDraw=${session.awaitingActivityDraw}" +
      " attached=${view.isAttachedToWindow} size=${view.width}x${view.height}" +
      " children=${(view as? ViewGroup)?.childCount}" +
      " parent=${view.parent?.javaClass?.simpleName}"
  }

  private fun ownerLabel(owner: Any?): String =
    owner?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it).toString(16)}" } ?: "none"

  private fun trace(event: String, session: Session) {
    Log.d("ClockSession", "$event session=${session.token.id}" +
      " owner=${ownerLabel(session.ownership.owner)} handingOff=${session.ownership.handingOff}")
  }

  private fun checkPhysicalScreenOff(session: Session) {
    session.screenOffCheck?.let(mainHandler::removeCallbacks)
    // Entering a bright Dream can emit SCREEN_OFF without turning off the physical panel.
    // Display state can lag that broadcast, so check after the window/power transition settles.
    val check = Runnable {
      session.screenOffCheck = null
      if (matching(session.token) !== session || session.ownership.owner is FarragutDreamService) return@Runnable
      val display = session.host?.container?.display
        ?: session.context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
      when (display?.state) {
        Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> {
          if (SystemClock.uptimeMillis() < session.awakeBootstrapUntilMillis) {
            trace("defer SCREEN_OFF during awake bootstrap", session)
            checkPhysicalScreenOff(session)
          } else end(session.token, ClockSessionEndReason.SCREEN_OFF)
        }
        else -> trace("ignore SCREEN_OFF with visible display", session)
      }
    }
    session.screenOffCheck = check
    mainHandler.postDelayed(check, 150L)
  }
}
