package com.example.farragutclock

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Date
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

enum class YtmBridgePhase { IDLE, NAVIGATING, LISTENING, READING, COMPLETE, FAILED }

data class YtmBridgeState(
  val phase: YtmBridgePhase = YtmBridgePhase.IDLE,
  val message: String = "",
  val title: String = "",
  val artist: String = "",
)

/**
 * Google owns microphone capture and matching. Each request is bounded to a single recognition.
 * Manual tests use a small clock overlay; clock sessions keep their complete shared scene visible.
 * The bridge never saves screenshots, starts playback, or changes volume/accounts.
 */
class YouTubeMusicBridgeService : AccessibilityService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var attemptJob: Job? = null
  private var tickerJob: Job? = null
  private var generation = 0L
  private var activeAttempt = false
  private var automaticAttempt: AutomaticBridgeAttempt? = null
  private var overlay: View? = null
  private var overlayLayoutParams: WindowManager.LayoutParams? = null
  private var overlaySnapshot: Bitmap? = null
  private val retiredOverlaySnapshots = mutableListOf<Bitmap>()
  private var returningToken: ClockSessionToken? = null
  private var returnWatchdog: Runnable? = null
  private var returnFrameWait: CommittedClockFrame? = null
  private var diagnosticStage = YtmDiagnosticStage.IDLE
  private var lastFailureStage: YtmDiagnosticStage? = null
  private var returnDecision = YtmReturnDecision.NONE
  private var returnForeground = YtmReturnForeground.UNKNOWN
  private var lastRecognitionDebug: YtmRecognitionDebug? = null
  private val navigationDebug = ArrayDeque<YtmNavigationDebug>()
  private var lastNavigationControls: List<YtmKnownNavigationControl> = emptyList()
  private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

  override fun onServiceConnected() {
    super.onServiceConnected()
    connectedService = this
    connectedState.value = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // Polling a bounded, package-checked window avoids retaining framework event/node objects.
  }

  override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
    super.dump(fd, writer, args)
    val view = overlay
    writer.println("YtmBridge phase=${bridgeState.value.phase} active=$activeAttempt automaticToken=${automaticAttempt?.token?.id}")
    writer.println("YtmBridge overlayAttached=${view?.isAttachedToWindow == true} size=${view?.width ?: 0}x${view?.height ?: 0} returningToken=${returningToken?.id}")
    writer.println("YtmBridge stage=$diagnosticStage failureStage=$lastFailureStage return=$returnDecision foreground=$returnForeground")
    writer.println("YtmBridge message=${bridgeState.value.message}")
    writer.println("YtmBridge clock=${ClockSession.debugSummary()}")
    if (args.any { it == "--navigation-debug" }) {
      if (!isDebuggableBuild()) writer.println("YtmBridge navigationDebug=unavailable")
      else {
        navigationDebug.forEach { debug ->
          writer.println("YtmBridge navigationDebug elapsed=${debug.elapsedMillis} nodes=${debug.nodes} focused=${debug.focused} active=${debug.active} search=${debug.searchControls} sound=${debug.soundControls} result=${debug.result} searchClicks=${debug.searchClicks} requested=${debug.requested} lastAction=${debug.lastAction} accepted=${debug.lastAccepted}")
        }
        lastNavigationControls.forEach { control ->
          writer.println("YtmBridge navigationControl label=${control.label} class=${control.viewClass} clickable=${control.clickable} bounds=${control.bounds.toShortString()} clickableParent=${control.clickableAncestor?.toShortString() ?: "none"}")
        }
      }
    }
    if (args.any { it == "--recognition-debug" }) {
      if (!isDebuggableBuild()) {
        writer.println("YtmBridge recognitionDebug=unavailable")
      } else {
        val debug = lastRecognitionDebug
        if (debug == null) writer.println("YtmBridge recognitionDebug=none")
        else {
          writer.println("YtmBridge recognitionDebug outcome=${debug.outcome} titleHint=${quotedRecognitionDebug(debug.titleHint)}")
          debug.geometry?.let { geometry ->
            writer.println("YtmBridge recognitionDebug window=${geometry.windowBounds.toShortString()} full=${geometry.fullWidth}x${geometry.fullHeight} crop=${geometry.cropRect.toShortString()}")
            writer.println("YtmBridge recognitionDebug root=${geometry.rootBounds.toShortString()} anchor=${geometry.anchorBounds.toShortString()} artwork=${geometry.artworkBounds.toShortString()}")
            writer.println("YtmBridge recognitionDebug inWindowRoot=${geometry.rootBoundsInWindow.toShortString()} inWindowAnchor=${geometry.anchorBoundsInWindow.toShortString()} inWindowArtwork=${geometry.artworkBoundsInWindow.toShortString()}")
          }
          debug.lines.forEachIndexed { index, line ->
            writer.println("YtmBridge recognitionDebug line[$index]=${quotedRecognitionDebug(line.text)} bounds=${line.left},${line.top},${line.right},${line.bottom}")
          }
        }
      }
    }
  }

  override fun onInterrupt() {
    if (activeAttempt) stopAttempt(
      YtmBridgeState(YtmBridgePhase.FAILED, "The YouTube Music test was interrupted."),
      returnToTest = automaticAttempt != null,
    )
  }

  override fun onUnbind(intent: Intent?): Boolean {
    disconnected()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    disconnected()
    scope.cancel()
    super.onDestroy()
  }

  private fun disconnected() {
    lastRecognitionDebug = null
    navigationDebug.clear()
    lastNavigationControls = emptyList()
    if (connectedService === this) {
      connectedService = null
      connectedState.value = false
    }
    if (activeAttempt) {
      stopAttempt(
        YtmBridgeState(YtmBridgePhase.FAILED, "Accessibility access was disconnected."),
        automaticAttempt != null,
      )
    } else if (returningToken == null) removeOverlay()
  }

  private fun beginAttempt(automatic: AutomaticBridgeAttempt? = null) {
    if (activeAttempt || overlay != null) {
      automatic?.continuation?.takeIf { it.isActive }?.resumeWithException(
        IOException("Another YouTube Music recognition is already running."),
      )
      return
    }
    if (Build.VERSION.SDK_INT < 34) {
      publish(YtmBridgeState(YtmBridgePhase.FAILED, "This experiment needs Android 14 or newer."))
      automatic?.continuation?.takeIf { it.isActive }?.resumeWithException(
        IOException("YouTube Music recognition needs Android 14 or newer."),
      )
      return
    }
    if (automatic != null && !ClockSession.beginRecognitionHandoff(automatic.token)) {
      automatic.continuation.takeIf { it.isActive }?.resumeWithException(
        IOException("The clock session is no longer active."),
      )
      return
    }
    val id = ++generation
    lastRecognitionDebug = null
    navigationDebug.clear()
    lastNavigationControls = emptyList()
    diagnosticStage = YtmDiagnosticStage.OVERLAY_SETUP
    lastFailureStage = null
    returnDecision = YtmReturnDecision.NONE
    returnForeground = YtmReturnForeground.UNKNOWN
    automaticAttempt = automatic
    activeAttempt = true
    publish(YtmBridgeState(YtmBridgePhase.NAVIGATING, "Opening YouTube Music…"))
    if (automatic == null) {
      try {
        showOverlay(null)
        launchYouTubeMusic()
      } catch (_: Exception) {
        stopAttempt(YtmBridgeState(YtmBridgePhase.FAILED, "YouTube Music could not be opened."), true)
        return
      }
    }
    attemptJob = scope.launch {
      var returnToTest = true
      var song: RecognizedSong? = null
      var error: Exception? = null
      try {
        song = withTimeout(45_000L) {
          if (automatic != null) prepareAutomaticHandoff(automatic)
          recognizeOneSong(automatic)
        }
        if (id == generation) publish(YtmBridgeState(
          YtmBridgePhase.COMPLETE, "YouTube Music identified this song.", song.title, song.artist,
        ))
      } catch (_: TimeoutCancellationException) {
        lastFailureStage = diagnosticStage
        error = IOException("YouTube Music recognition timed out.")
        if (id == generation) publish(YtmBridgeState(
          YtmBridgePhase.FAILED,
          if (bridgeState.value.phase == YtmBridgePhase.READING)
            "YouTube Music found a song, but its title and artist could not be safely read."
          else "YouTube Music did not return a readable song within 45 seconds.",
        ))
      } catch (_: YtmLaunchException) {
        lastFailureStage = diagnosticStage
        error = IOException("YouTube Music did not come to the foreground.")
        if (id == generation) publish(YtmBridgeState(
          YtmBridgePhase.FAILED, "YouTube Music did not come to the foreground. Please try again.",
        ))
      } catch (_: LeftYouTubeMusicException) {
        lastFailureStage = diagnosticStage
        returnToTest = false
        error = CancellationException("The clock session was left.")
        if (id == generation) publish(YtmBridgeState(
          YtmBridgePhase.FAILED, "Test stopped because another app was opened.",
        ))
      } catch (cancelled: CancellationException) {
        returnToTest = false
        error = cancelled
        throw cancelled
      } catch (_: Exception) {
        lastFailureStage = diagnosticStage
        error = IOException("The YouTube Music result could not be read.")
        if (id == generation) publish(YtmBridgeState(
          YtmBridgePhase.FAILED, "The YouTube Music result could not be read. Please try again.",
        ))
      } finally {
        if (id == generation) {
          activeAttempt = false
          attemptJob = null
          automaticAttempt = null
          if (automatic != null) {
            if (returnToTest && ClockSession.isActive(automatic.token)) {
              returnToClockSession(automatic.token)
            } else {
              diagnosticStage = YtmDiagnosticStage.STOPPED
              returnDecision = if (returnToTest) YtmReturnDecision.DENIED_SESSION else YtmReturnDecision.DEPARTED
              removeOverlay()
              ClockSession.end(automatic.token)
            }
            if (automatic.continuation.isActive) {
              if (error != null) automatic.continuation.resumeWithException(error)
              else automatic.continuation.resume(song)
            }
          } else {
            removeOverlay()
            if (returnToTest) returnToTestActivity()
          }
        }
      }
    }
  }

  private fun launchYouTubeMusic() {
    diagnosticStage = YtmDiagnosticStage.YTM_LAUNCH
    startActivity(Intent(Intent.ACTION_MAIN).setClassName(YTM_PACKAGE, YTM_ACTIVITY)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
      ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
  }

  /** ViewRoot attachment/drawing happens after addView returns, on a later main-loop traversal. */
  private suspend fun prepareAutomaticHandoff(automatic: AutomaticBridgeAttempt) {
    try {
      diagnosticStage = YtmDiagnosticStage.OVERLAY_SETUP
      val needsAwakeBootstrap = ClockSession.needsAwakeBootstrap(automatic.token)
      showOverlay(automatic)
      val view = overlay ?: throw YtmLaunchException()
      try {
        diagnosticStage = YtmDiagnosticStage.WAITING_OVERLAY_FRAME
        withTimeout(3_000L) { awaitOverlayFrame(view) }
      } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw YtmLaunchException()
      }
      currentCoroutineContext().ensureActive()
      if (!ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
      // The committed snapshot covers the original host before removing its live scene.
      diagnosticStage = YtmDiagnosticStage.REATTACH_OVERLAY
      val container = view as? FrameLayout ?: throw YtmLaunchException()
      val params = overlayLayoutParams ?: throw YtmLaunchException()
      if (!attachAutomaticScene(automatic, container, params)) throw YtmLaunchException()
      diagnosticStage = YtmDiagnosticStage.WAITING_OVERLAY_FRAME
      try {
        withTimeout(3_000L) { awaitOverlayFrame(view) }
      } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw YtmLaunchException()
      }
      if (!ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
      clearOverlaySnapshot(view)

      if (needsAwakeBootstrap) {
        // Ending an idle Dream may otherwise complete sleep before an ordinary YTM Activity starts.
        // Commit the frozen cover before moving the live scene into our turnScreenOn Activity.
        if (!freezeOverlayFrame(view)) throw YtmLaunchException()
        diagnosticStage = YtmDiagnosticStage.WAITING_OVERLAY_FRAME
        withTimeout(3_000L) { awaitOverlayFrame(view) }
        diagnosticStage = YtmDiagnosticStage.WAITING_AWAKE_CLOCK
        withTimeout(3_000L) { awaitAwakeClock(automatic.token, view) }
        // Accessibility focus publication may trail the Activity's committed frame.
        delay(300L)
        currentCoroutineContext().ensureActive()
        if (!ClockSession.isActive(automatic.token) || !automaticForegroundAllowsReturn()) {
          throw LeftYouTubeMusicException()
        }
        diagnosticStage = YtmDiagnosticStage.REATTACH_OVERLAY
        if (!ClockSession.beginRecognitionHandoff(automatic.token)) throw YtmLaunchException()
        if (!attachAutomaticScene(automatic, container, params)) throw YtmLaunchException()
        diagnosticStage = YtmDiagnosticStage.WAITING_OVERLAY_FRAME
        withTimeout(3_000L) { awaitOverlayFrame(view) }
        if (!ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
        clearOverlaySnapshot(view)
      }
      if (!ClockSession.isActive(automatic.token) || !automaticForegroundAllowsReturn()) {
        throw LeftYouTubeMusicException()
      }
      launchYouTubeMusic()
      // Dream dismissal can expose Home briefly while the already-requested YTM activity resumes.
      // Delay the first check, then apply the normal strict foreground rules without a Home exception.
      delay(300L)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (left: LeftYouTubeMusicException) {
      throw left
    } catch (_: Exception) {
      throw YtmLaunchException()
    }
  }

  private suspend fun awaitAwakeClock(token: ClockSessionToken, expectedOverlay: View): Unit =
    suspendCancellableCoroutine { continuation ->
      val requested = ClockSession.bootstrapAwakeClock(token, this) {
        if (continuation.isActive) {
          if (ClockSession.isActive(token) && overlay === expectedOverlay) continuation.resume(Unit)
          else continuation.resumeWithException(LeftYouTubeMusicException())
        }
      }
      if (!requested && continuation.isActive) continuation.resumeWithException(YtmLaunchException())
      // ClockSession clears/replaces this one-shot callback on end/recovery. A cancelled request
      // cannot resume here, and the enclosing attempt cancellation performs overlay/session cleanup.
    }

  private suspend fun awaitOverlayFrame(view: View): Unit = suspendCancellableCoroutine { continuation ->
    val committedFrame = CommittedClockFrame(view, onReady = {
      if (continuation.isActive) {
        if (overlay === view && view.isAttachedToWindow && view.width > 0 && view.height > 0) {
          continuation.resume(Unit)
        } else continuation.resumeWithException(YtmLaunchException())
      }
    }, onUnavailable = {
      if (continuation.isActive) continuation.resumeWithException(YtmLaunchException())
    })
    continuation.invokeOnCancellation { mainHandler.post { committedFrame.cancel() } }
  }

  @RequiresApi(34)
  private suspend fun recognizeOneSong(automatic: AutomaticBridgeAttempt?): RecognizedSong {
    diagnosticStage = YtmDiagnosticStage.NAVIGATING
    val startedAt = SystemClock.elapsedRealtime()
    var sawYtm = false
    var requestedRecognition = false
    var requireFreshTransition = false
    var sawFreshTransition = false
    var lastClickAt = Long.MIN_VALUE
    var lastReadAt = Long.MIN_VALUE
    var navigationClicks = 0
    var lastNavigationAction = YtmNavigationAction.NONE
    var lastNavigationAccepted: Boolean? = null
    while (scope.isActive) {
      if (automatic != null && !ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
      val now = SystemClock.elapsedRealtime()
      // During the initial Dream dismissal, its own window may briefly retain focus even if a
      // YTM task already exists below it. Do not act on that covered YTM window or call it departure.
      val window = awaitYtmWindow(automatic, allowInitialClock = !sawYtm)
      if (window == null) {
        if (!sawYtm && now - startedAt >= 5_000L) throw YtmLaunchException()
        delay(300)
        continue
      }
      sawYtm = true
      val screen = readYtmScreen(window)
      if (screen == null) {
        delay(300)
        continue
      }
      val isResult = screen.nodes.any { it.text == "Search another song" } &&
        screen.nodes.any { it.description == "Save to playlist" } &&
        screen.nodes.any { it.description in RESULT_LIKE_DESCRIPTIONS }

      if (requestedRecognition && !isResult) sawFreshTransition = true
      if (requestedRecognition && isResult && (!requireFreshTransition || sawFreshTransition) &&
          now - lastClickAt >= 900L) {
        publish(YtmBridgeState(YtmBridgePhase.READING, "Reading the identified song…"))
        val anchor = findResultAnchor(screen.nodes)
        if (anchor == null) diagnosticStage = YtmDiagnosticStage.RESULT_ANCHOR
        if (anchor != null && (lastReadAt == Long.MIN_VALUE || now - lastReadAt >= 2_500L)) {
          lastReadAt = now
          if (isDebuggableBuild()) lastRecognitionDebug = YtmRecognitionDebug(anchor.description.take(300))
          diagnosticStage = YtmDiagnosticStage.SCREENSHOT
          val bitmap = screenshotMetadata(window, anchor)
          // If the user changed app during screenshot/OCR, no result may escape this attempt.
          try {
            awaitYtmWindow(automatic) ?: throw LeftYouTubeMusicException()
          } catch (error: Exception) {
            bitmap.recycle()
            throw error
          }
          val dimensions = bitmap.width to bitmap.height
          diagnosticStage = YtmDiagnosticStage.OCR
          val lines = recognizeText(bitmap)
          awaitYtmWindow(automatic) ?: throw LeftYouTubeMusicException()
          if (automatic != null && !ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
          diagnosticStage = YtmDiagnosticStage.PARSING
          val parsed = parseYtmResult(anchor.description, lines, dimensions.first, dimensions.second)
          if (isDebuggableBuild() && connectedService === this && activeAttempt) {
            lastRecognitionDebug = lastRecognitionDebug?.copy(
              lines = lines.take(12).map { it.copy(text = it.text.take(300)) },
              outcome = if (parsed == null) YtmDebugOutcome.REJECTED else YtmDebugOutcome.PARSED,
            )
          }
          parsed?.let { return it }
        }
      } else if (!requestedRecognition &&
          (lastClickAt == Long.MIN_VALUE || now - lastClickAt >= 1_200L)) {
        // These are the only controls the bridge can click. No playback/account/dialog actions.
        val navigationControls = screen.nodes.map { it.navigationControl() }
        val soundTarget = selectYtmSoundSearchTarget(navigationControls, screen.rootBounds.navigationBounds())
        val soundClicked = if (soundTarget != null) {
          lastNavigationAction = YtmNavigationAction.SOUND_SEARCH
          clickYtmNode(window, screen.nodes[soundTarget.index]).also { lastNavigationAccepted = it }
        } else false
        if (soundClicked) {
          requestedRecognition = true
          requireFreshTransition = isResult
          lastClickAt = now
          diagnosticStage = YtmDiagnosticStage.LISTENING
          publish(YtmBridgeState(YtmBridgePhase.LISTENING, "Listening with YouTube Music…"))
        } else if (navigationClicks < 3) {
          val searchTarget = selectYtmSearchTarget(navigationControls, screen.rootBounds.navigationBounds())
          val searchClicked = if (searchTarget != null) {
            lastNavigationAction = searchTarget.action
            clickYtmNode(window, screen.nodes[searchTarget.index], searchTarget.clickBounds.rect())
              .also { lastNavigationAccepted = it }
          } else false
          if (searchClicked) {
            navigationClicks++
            lastClickAt = now
            publish(YtmBridgeState(YtmBridgePhase.NAVIGATING, "Opening Sound Search…"))
          }
        }
      }
      recordNavigationDebug(window, screen, now - startedAt, isResult, navigationClicks,
        requestedRecognition, lastNavigationAction, lastNavigationAccepted)
      delay(350)
    }
    throw CancellationException()
  }

  /** A newly scheduled Dream may briefly take focus before ClockSession rejects and finishes it. */
  private suspend fun awaitYtmWindow(
    automatic: AutomaticBridgeAttempt?,
    allowInitialClock: Boolean = false,
  ): AccessibilityWindowInfo? {
    val deadline = SystemClock.elapsedRealtime() + 1_000L
    val previousStage = diagnosticStage
    while (true) {
      if (automatic != null && !ClockSession.isActive(automatic.token)) throw LeftYouTubeMusicException()
      var ownClockHasFocus = false
      val window = ytmWindowOrCheckDeparture(
        stopIfOtherApp = true,
        allowStartingClock = allowInitialClock || automatic != null,
        onOwnClockFocus = { ownClockHasFocus = true },
      )
      if (!ownClockHasFocus || automatic == null || allowInitialClock) {
        if (diagnosticStage == YtmDiagnosticStage.WAITING_OWN_CLOCK) diagnosticStage = previousStage
        return window
      }
      diagnosticStage = YtmDiagnosticStage.WAITING_OWN_CLOCK
      if (SystemClock.elapsedRealtime() >= deadline) throw LeftYouTubeMusicException()
      // No YTM UI actions occur while its window is covered by the transient clock host.
      delay(100L)
    }
  }

  /** Inspect only a root package name for other apps, never their text or descendants. */
  private fun ytmWindowOrCheckDeparture(
    stopIfOtherApp: Boolean,
    allowStartingClock: Boolean = false,
    onOwnClockFocus: (() -> Unit)? = null,
  ): AccessibilityWindowInfo? {
    var candidate: AccessibilityWindowInfo? = null
    var startingClockHasFocus = false
    for (window in windows) {
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
      val root = window.root ?: continue
      val owner = try { root.packageName?.toString() } finally { root.recycle() }
      if (owner == YTM_PACKAGE) candidate = window
      else if (window.isActive || window.isFocused) {
        if (allowStartingClock && owner == packageName) {
          startingClockHasFocus = true
          onOwnClockFocus?.invoke()
        }
        else if (stopIfOtherApp) throw LeftYouTubeMusicException()
      }
    }
    return if (startingClockHasFocus) null else candidate
  }

  private fun readYtmScreen(window: AccessibilityWindowInfo): YtmScreen? {
    val root = window.root ?: return null
    try {
      if (root.packageName?.toString() != YTM_PACKAGE) return null
      val nodes = mutableListOf<YtmNode>()
      collectNodes(root, nodes)
      val bounds = Rect()
      root.getBoundsInScreen(bounds)
      val inWindow = Rect()
      if (Build.VERSION.SDK_INT >= 34) root.getBoundsInWindow(inWindow)
      return YtmScreen(nodes, bounds, inWindow)
    } finally {
      root.recycle()
    }
  }

  private fun collectNodes(
    node: AccessibilityNodeInfo,
    nodes: MutableList<YtmNode>,
    depth: Int = 0,
    clickableAncestor: Rect? = null,
  ) {
    if (nodes.size >= 500 || depth > 40 || node.packageName?.toString() != YTM_PACKAGE) return
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val inWindow = Rect()
    if (Build.VERSION.SDK_INT >= 34) node.getBoundsInWindow(inWindow)
    if (node.isVisibleToUser && !node.isPassword) nodes += YtmNode(
      text = node.text?.toString()?.take(400).orEmpty(),
      description = node.contentDescription?.toString()?.take(400).orEmpty(),
      className = node.className?.toString().orEmpty(),
      clickable = node.isClickable && node.isEnabled,
      bounds = bounds,
      boundsInWindow = inWindow,
      clickableAncestor = clickableAncestor,
    )
    val nextAncestor = if (node.isVisibleToUser && node.isEnabled && node.isClickable) bounds else clickableAncestor
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      try { collectNodes(child, nodes, depth + 1, nextAncestor) } finally { child.recycle() }
    }
  }

  private fun clickYtmNode(
    window: AccessibilityWindowInfo,
    target: YtmNode,
    clickBounds: Rect = target.bounds,
  ): Boolean {
    val root = window.root ?: return false
    return try { clickMatchingNode(root, target, clickBounds) } finally { root.recycle() }
  }

  private fun clickMatchingNode(
    node: AccessibilityNodeInfo,
    target: YtmNode,
    clickBounds: Rect,
    depth: Int = 0,
  ): Boolean {
    if (depth > 40 || node.packageName?.toString() != YTM_PACKAGE) return false
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    if (node.isVisibleToUser && node.isEnabled && bounds == target.bounds &&
        node.contentDescription?.toString().orEmpty() == target.description &&
        node.text?.toString().orEmpty() == target.text) {
      if (clickBounds == bounds) return node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      return clickBoundedAncestor(node, clickBounds)
    }
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      val clicked = try { clickMatchingNode(child, target, clickBounds, depth + 1) } finally { child.recycle() }
      if (clicked) return true
    }
    return false
  }

  /** Only an ancestor prevalidated as one small tab in the verified navigation bar is eligible. */
  private fun clickBoundedAncestor(node: AccessibilityNodeInfo, clickBounds: Rect): Boolean {
    var ancestor = node.parent
    var depth = 0
    while (ancestor != null && depth++ < 8) {
      val current = ancestor
      ancestor = null
      try {
        if (current.packageName?.toString() != YTM_PACKAGE) return false
        val bounds = Rect()
        current.getBoundsInScreen(bounds)
        if (bounds == clickBounds) return current.isVisibleToUser && current.isEnabled &&
          current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ancestor = current.parent
      } finally { current.recycle() }
    }
    ancestor?.recycle()
    return false
  }

  private fun recordNavigationDebug(
    window: AccessibilityWindowInfo,
    screen: YtmScreen,
    elapsedMillis: Long,
    isResult: Boolean,
    searchClicks: Int,
    requested: Boolean,
    lastAction: YtmNavigationAction,
    lastAccepted: Boolean?,
  ) {
    if (!isDebuggableBuild()) return
    val controls = screen.nodes.mapNotNull { node ->
      val label = when {
        node.description == SOUND_SEARCH_DESCRIPTION ||
          node.description == YTM_TOOLBAR_SOUND_SEARCH_DESCRIPTION -> YtmKnownNavigationLabel.SOUND_SEARCH
        node.text == "Search" || node.description == "Search" -> YtmKnownNavigationLabel.SEARCH
        node.text == "Home" || node.description == "Home" -> YtmKnownNavigationLabel.HOME
        node.text == "Library" || node.description == "Library" -> YtmKnownNavigationLabel.LIBRARY
        node.text == "Samples" || node.description == "Samples" -> YtmKnownNavigationLabel.SAMPLES
        node.text == "Search another song" -> YtmKnownNavigationLabel.SEARCH_ANOTHER_SONG
        else -> null
      } ?: return@mapNotNull null
      val viewClass = when (node.className) {
        "android.widget.TextView" -> YtmNavigationClass.TEXT
        "android.widget.ImageView" -> YtmNavigationClass.IMAGE
        "android.widget.ImageButton" -> YtmNavigationClass.IMAGE_BUTTON
        "android.widget.EditText" -> YtmNavigationClass.EDIT_TEXT
        "android.view.View" -> YtmNavigationClass.VIEW
        "android.view.ViewGroup" -> YtmNavigationClass.VIEW_GROUP
        else -> YtmNavigationClass.OTHER
      }
      YtmKnownNavigationControl(label, viewClass, node.clickable, Rect(node.bounds),
        node.clickableAncestor?.let(::Rect))
    }.take(16)
    lastNavigationControls = controls
    navigationDebug.addLast(YtmNavigationDebug(elapsedMillis, screen.nodes.size, window.isFocused,
      window.isActive, controls.count { it.label == YtmKnownNavigationLabel.SEARCH },
      controls.count { it.label == YtmKnownNavigationLabel.SOUND_SEARCH }, isResult,
      searchClicks, requested, lastAction, lastAccepted))
    while (navigationDebug.size > 16) navigationDebug.removeFirst()
  }

  private fun Rect.navigationBounds() = YtmNavigationBounds(left, top, right, bottom)
  private fun YtmNavigationBounds.rect() = Rect(left, top, right, bottom)
  private fun YtmNode.navigationControl() = YtmNavigationControl(text, description, clickable,
    bounds.navigationBounds(), clickableAncestor?.navigationBounds())

  private fun findResultAnchor(nodes: List<YtmNode>): YtmNode? = nodes.filter { node ->
    node.description.isNotBlank() && node.description.length <= 300 &&
      node.description != SOUND_SEARCH_DESCRIPTION && node.description !in RESULT_CONTROL_DESCRIPTIONS &&
      node.bounds.width() >= 150 && node.bounds.height() >= node.bounds.width() &&
      node.bounds.height() <= node.bounds.width() * 2 && artworkFor(node, nodes) != null
  }.singleOrNull()

  private fun artworkFor(anchor: YtmNode, nodes: List<YtmNode>): YtmNode? = nodes.filter {
    it.className == "android.widget.ImageView" && anchor.bounds.contains(it.bounds) &&
      it.bounds.width() >= anchor.bounds.width() * 0.85 &&
      it.bounds.height() >= it.bounds.width() * 0.85 &&
      it.bounds.height() <= it.bounds.width() * 1.15 && it.bounds.bottom < anchor.bounds.bottom
  }.maxByOrNull { it.bounds.width() * it.bounds.height() }

  @RequiresApi(34)
  private suspend fun screenshotMetadata(window: AccessibilityWindowInfo, anchor: YtmNode): Bitmap {
    val screen = readYtmScreen(window) ?: throw IllegalStateException()
    val artwork = artworkFor(anchor, screen.nodes) ?: throw IllegalStateException()
    val windowBounds = Rect()
    window.getBoundsInScreen(windowBounds)
    val full = screenshotWindow(window.id)
    try {
      if (windowBounds.width() <= 0 || windowBounds.height() <= 0) throw IllegalStateException()
      val scaleX = full.width.toFloat() / windowBounds.width()
      val scaleY = full.height.toFloat() / windowBounds.height()
      val left = ((anchor.bounds.left - windowBounds.left) * scaleX).toInt().coerceIn(0, full.width)
      val right = ((anchor.bounds.right - windowBounds.left) * scaleX).toInt().coerceIn(0, full.width)
      val top = ((artwork.bounds.bottom - windowBounds.top - 4) * scaleY).toInt().coerceIn(0, full.height)
      val bottom = ((anchor.bounds.bottom - windowBounds.top + 6) * scaleY).toInt().coerceIn(0, full.height)
      if (isDebuggableBuild() && connectedService === this && activeAttempt) {
        lastRecognitionDebug = lastRecognitionDebug?.copy(geometry = YtmDebugGeometry(
          Rect(windowBounds), full.width, full.height, Rect(left, top, right, bottom),
          Rect(screen.rootBounds), Rect(anchor.bounds), Rect(artwork.bounds),
          Rect(screen.rootBoundsInWindow), Rect(anchor.boundsInWindow), Rect(artwork.boundsInWindow),
        ))
      }
      if (right - left < 80 || bottom - top < 32 || bottom - top > full.height / 3) throw IllegalStateException()
      return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    } finally {
      full.recycle()
    }
  }

  @RequiresApi(34)
  private suspend fun screenshotWindow(windowId: Int): Bitmap = suspendCancellableCoroutine { continuation ->
    takeScreenshotOfWindow(windowId, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
      override fun onSuccess(screenshot: ScreenshotResult) {
        var hardwareBitmap: Bitmap? = null
        var bitmap: Bitmap? = null
        try {
          hardwareBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
          bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
          if (bitmap == null) throw IllegalStateException()
          if (continuation.isActive) {
            continuation.resume(bitmap) { _, unusedBitmap, _ -> unusedBitmap.recycle() }
          } else bitmap.recycle()
        } catch (_: Exception) {
          bitmap?.recycle()
          if (continuation.isActive) continuation.resumeWithException(IllegalStateException())
        } finally {
          hardwareBitmap?.recycle()
          screenshot.hardwareBuffer.close()
        }
      }

      override fun onFailure(errorCode: Int) {
        if (continuation.isActive) continuation.resumeWithException(IllegalStateException())
      }
    })
  }

  /** Task completion owns image disposal, including when the coroutine has already been cancelled. */
  private suspend fun recognizeText(bitmap: Bitmap): List<YtmOcrLine> = suspendCancellableCoroutine { continuation ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { text ->
          val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { YtmOcrLine(line.text, it.left, it.top, it.right, it.bottom) }
          }
          if (continuation.isActive) continuation.resume(lines)
        }
        .addOnFailureListener {
          if (continuation.isActive) continuation.resumeWithException(IllegalStateException())
        }
        .addOnCompleteListener {
          recognizer.close()
          bitmap.recycle()
        }
    } catch (_: Exception) {
      recognizer.close()
      bitmap.recycle()
      if (continuation.isActive) continuation.resumeWithException(IllegalStateException())
    }
  }

  private fun publish(value: YtmBridgeState) {
    bridgeState.value = value
    (overlay as? BridgeOverlay)?.render(value)
  }

  private fun stopAttempt(value: YtmBridgeState, returnToTest: Boolean) {
    val automatic = automaticAttempt
    generation++
    activeAttempt = false
    automaticAttempt = null
    attemptJob?.cancel()
    attemptJob = null
    if (value.phase == YtmBridgePhase.FAILED) lastFailureStage = diagnosticStage
    diagnosticStage = YtmDiagnosticStage.STOPPED
    publish(value)
    if (automatic != null) {
      if (returnToTest && ClockSession.isActive(automatic.token)) returnToClockSession(automatic.token)
      else {
        returnDecision = if (returnToTest) YtmReturnDecision.DENIED_SESSION else YtmReturnDecision.DEPARTED
        removeOverlay()
        ClockSession.end(automatic.token)
      }
      automatic.continuation.cancel(CancellationException(value.message))
    } else {
      removeOverlay()
      if (returnToTest) returnToTestActivity()
    }
  }

  private fun showOverlay(automatic: AutomaticBridgeAttempt?) {
    val view: View = if (automatic == null) BridgeOverlay(this) {
      stopAttempt(YtmBridgeState(message = "YouTube Music test stopped."), true)
    } else FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
      PixelFormat.OPAQUE,
    )
    params.gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0)
    params.windowAnimations = 0
    if (automatic != null) {
      // Keep the scene in its original visible window until this opaque copy is committed.
      val frame = ClockSession.snapshotRecognitionFrame(automatic.token) ?: throw YtmLaunchException()
      overlaySnapshot = frame
      view.foreground = BitmapDrawable(resources, frame)
      params.screenBrightness = ClockPreferences.nightBrightness(this)
    }
    try {
      windowManager.addView(view, params)
    } catch (error: Exception) {
      // The shared scene is still owned by ClockSession and is returned by the caller's cleanup.
      throw error
    }
    overlay = view
    overlayLayoutParams = params
    if (view is BridgeOverlay) {
      view.render(bridgeState.value)
      tickerJob = scope.launch {
        while (isActive) {
          view.tick()
          delay(500)
        }
      }
    }
  }

  private fun attachAutomaticScene(
    automatic: AutomaticBridgeAttempt,
    container: FrameLayout,
    params: WindowManager.LayoutParams,
  ): Boolean = ClockSession.attachRecognitionOverlay(automatic.token, container) { brightness ->
    if (params.screenBrightness != brightness) {
      params.screenBrightness = brightness
      if (container.isAttachedToWindow) runCatching { windowManager.updateViewLayout(container, params) }
    }
  }

  /** Keep a local clock frame visible while its live scene moves into the returning activity. */
  private fun returnToClockSession(token: ClockSessionToken) {
    // Cancellation may beat the polling loop after Home/another app has already taken focus.
    // A still-live handoff token alone must never bring the clock back over that user departure.
    diagnosticStage = YtmDiagnosticStage.RETURN_SESSION_CHECK
    if (!ClockSession.isActive(token)) {
      returnDecision = YtmReturnDecision.DENIED_SESSION
      removeOverlay()
      ClockSession.end(token)
      return
    }
    diagnosticStage = YtmDiagnosticStage.RETURN_FOREGROUND_CHECK
    if (!automaticForegroundAllowsReturn()) {
      returnDecision = YtmReturnDecision.DENIED_FOREGROUND
      removeOverlay()
      ClockSession.end(token)
      return
    }
    val returningOverlay = overlay
    clearReturnWatchdog()
    returningToken = token
    fun failReturn(decision: YtmReturnDecision) {
      if (returningToken != token) return
      returnDecision = decision
      clearReturnWatchdog()
      if (overlay === returningOverlay) removeOverlay()
      ClockSession.end(token)
    }
    fun requestClockReturn() {
      if (returningToken != token) return
      // Freezing/committing is asynchronous; source cancellation or Home can win meanwhile.
      if (!ClockSession.isActive(token)) {
        failReturn(YtmReturnDecision.DENIED_SESSION)
        return
      }
      if (!automaticForegroundAllowsReturn()) {
        failReturn(YtmReturnDecision.DENIED_FOREGROUND)
        return
      }
      diagnosticStage = YtmDiagnosticStage.RETURN_REQUEST
      val returning = ClockSession.returnToClock(token, this) {
        if (returningToken == token) {
          diagnosticStage = YtmDiagnosticStage.RETURN_DRAWN
          returnDecision = YtmReturnDecision.DRAWN
          clearReturnWatchdog()
          if (overlay === returningOverlay) removeOverlay()
        }
      }
      if (!returning) {
        failReturn(YtmReturnDecision.REQUEST_FAILED)
      } else if (returningToken == token) {
        diagnosticStage = YtmDiagnosticStage.RETURN_FIRST_DRAW
        returnDecision = YtmReturnDecision.WAITING_FIRST_DRAW
      }
    }
    val needsCommittedCover = returningOverlay?.isAttachedToWindow == true
    if (needsCommittedCover && !freezeOverlayFrame(checkNotNull(returningOverlay))) {
      returnDecision = YtmReturnDecision.REQUEST_FAILED
      failReturn(YtmReturnDecision.REQUEST_FAILED)
      return
    }
    // The focused clock keeps this cover for two seconds while system-bar animations settle.
    val deadline = SystemClock.elapsedRealtime() + 5_000L
    // Both the committed-cover wait and Activity return must outlive scope.cancel() in onDestroy.
    // The bounded main-handler watchdog also handles a hardware frame that never commits.
    val watchdog = object : Runnable {
      override fun run() {
        if (returningToken != token) return
        val stillActive = ClockSession.isActive(token)
        val departed = if (connectedService === this@YouTubeMusicBridgeService) {
          automaticForegroundAllowsReturn()
          // An application root can disappear briefly during a legitimate Activity transition.
          // Only a positively identified different app is a departure after return was requested.
          returnForeground == YtmReturnForeground.OTHER_APP
        } else false
        val expired = SystemClock.elapsedRealtime() >= deadline
        if (!stillActive || departed || expired) {
          returnDecision = when {
            !stillActive -> YtmReturnDecision.SESSION_ENDED
            departed -> YtmReturnDecision.DEPARTED
            else -> YtmReturnDecision.TIMEOUT
          }
          clearReturnWatchdog()
          if (overlay === returningOverlay) removeOverlay()
          if (departed || expired) ClockSession.end(token)
        } else {
          if (overlay === returningOverlay && returningOverlay?.isAttachedToWindow == true) {
            val params = overlayLayoutParams
            if (params != null) {
              // During the settle hold, taps may reach only our focused, committed native clock.
              // Keep blocking while YTM or an unfinished Activity transition is underneath.
              val passToClock = ClockSession.isClockInteractive(token)
              val oldFlags = params.flags
              params.flags = if (passToClock) {
                oldFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              } else {
                oldFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
              }
              if (params.flags != oldFlags) {
                runCatching { windowManager.updateViewLayout(returningOverlay, params) }
                  .onFailure { params.flags = oldFlags }
              }
            }
          }
          mainHandler.postDelayed(this, 100L)
        }
      }
    }
    returnWatchdog = watchdog
    mainHandler.postDelayed(watchdog, 300L)
    if (needsCommittedCover) {
      diagnosticStage = YtmDiagnosticStage.RETURN_COVER_FRAME
      val frameWait = CommittedClockFrame(checkNotNull(returningOverlay), onReady = {
        returnFrameWait = null
        requestClockReturn()
      }, onUnavailable = {
        failReturn(YtmReturnDecision.REQUEST_FAILED)
      })
      // Unsupported/detached windows can report unavailable during helper construction.
      if (returningToken == token) returnFrameWait = frameWait else frameWait.cancel()
    } else {
      requestClockReturn()
    }
  }

  /** Read only foreground root ownership; never inspect another app's UI text or descendants. */
  private fun automaticForegroundAllowsReturn(): Boolean = runCatching {
    var hasAllowedForeground = false
    for (window in windows) {
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
          (!window.isActive && !window.isFocused)) continue
      val root = window.root ?: run {
        returnForeground = YtmReturnForeground.ROOT_UNAVAILABLE
        return@runCatching false
      }
      val owner = try { root.packageName?.toString() } finally { root.recycle() }
      if (owner != YTM_PACKAGE && owner != packageName) {
        returnForeground = YtmReturnForeground.OTHER_APP
        return@runCatching false
      }
      hasAllowedForeground = true
    }
    returnForeground = if (hasAllowedForeground) YtmReturnForeground.ALLOWED else YtmReturnForeground.NO_APPLICATION_WINDOW
    hasAllowedForeground
  }.getOrElse {
    returnForeground = YtmReturnForeground.QUERY_FAILED
    false
  }

  private fun clearReturnWatchdog() {
    returnWatchdog?.let(mainHandler::removeCallbacks)
    returnWatchdog = null
    returningToken = null
    returnFrameWait?.cancel()
    returnFrameWait = null
  }

  private fun isDebuggableBuild(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Escape controls so an opted-in metadata dump is bounded plain text, never terminal commands. */
  private fun quotedRecognitionDebug(value: String): String = buildString {
    append('"')
    for (character in value.take(300)) {
      when {
        character == '\\' -> append("\\\\")
        character == '"' -> append("\\\"")
        character.code < 32 || character.code == 127 -> {
          append("\\u")
          append(character.code.toString(16).padStart(4, '0'))
        }
        else -> append(character)
      }
    }
    append('"')
  }

  private fun freezeOverlayFrame(view: View): Boolean {
    if (view.width <= 0 || view.height <= 0) return false
    return runCatching {
      val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
      try {
        view.draw(Canvas(frame))
        val previous = overlaySnapshot
        overlaySnapshot = frame
        // An opaque foreground avoids drawing antialiased live text over the same copied text.
        view.foreground = BitmapDrawable(resources, frame)
        // A previously submitted display list may still reference its bitmap until the cover ends.
        previous?.let(retiredOverlaySnapshots::add)
        true
      } catch (error: Throwable) {
        frame.recycle()
        throw error
      }
    }.getOrDefault(false)
  }

  private fun clearOverlaySnapshot(view: View) {
    view.foreground = null
    overlaySnapshot?.let(retiredOverlaySnapshots::add)
    overlaySnapshot = null
  }

  private fun removeOverlay() {
    tickerJob?.cancel()
    tickerJob = null
    overlay?.let { view ->
      runCatching { windowManager.removeViewImmediate(view) }
      view.foreground = null
    }
    overlay = null
    overlayLayoutParams = null
    overlaySnapshot?.recycle()
    overlaySnapshot = null
    retiredOverlaySnapshots.forEach(Bitmap::recycle)
    retiredOverlaySnapshots.clear()
  }

  private fun returnToTestActivity() {
    runCatching {
      startActivity(Intent().setClassName(packageName, "$packageName.YtmRecognitionTestActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
  }

  companion object {
    private const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
    private const val YTM_ACTIVITY = "$YTM_PACKAGE.activities.MusicActivity"
    private const val SOUND_SEARCH_DESCRIPTION = YTM_LONG_SOUND_SEARCH_DESCRIPTION
    private val RESULT_LIKE_DESCRIPTIONS = setOf("Like this video", "Unlike this video", "Video is liked")
    private val RESULT_CONTROL_DESCRIPTIONS = setOf(
      "Like this video", "Unlike this video", "Video is liked", "Save to playlist", "Sound search history",
      "Drag down to dismiss page", "Search", "Navigate up", "Back", "Close",
    )
    private val bridgeState = MutableStateFlow(YtmBridgeState())
    private val connectedState = MutableStateFlow(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var connectedService: YouTubeMusicBridgeService? = null

    val state: StateFlow<YtmBridgeState> = bridgeState.asStateFlow()
    val isConnected: StateFlow<Boolean> = connectedState.asStateFlow()

    /** Source/session cancellation cancels the YTM attempt without using this app's microphone. */
    internal suspend fun recognizeAutomatically(context: Context): RecognizedSong? =
      suspendCancellableCoroutine { continuation ->
        val appContext = context.applicationContext
        mainHandler.post {
          if (!continuation.isActive) return@post
          val service = connectedService
          val token = ClockSession.currentToken()
          if (service == null || service.packageName != appContext.packageName) {
            continuation.resumeWithException(IOException("Enable the YouTube Music bridge in Accessibility settings."))
            return@post
          }
          if (token == null || !ClockSession.isActive(token)) {
            continuation.resumeWithException(IOException("The clock session is no longer active."))
            return@post
          }
          val automatic = AutomaticBridgeAttempt(token, continuation)
          continuation.invokeOnCancellation {
            mainHandler.post {
              if (service.automaticAttempt === automatic) {
                service.stopAttempt(YtmBridgeState(message = "YouTube Music recognition stopped."), true)
              }
            }
          }
          if (continuation.isActive) service.beginAttempt(automatic)
        }
      }

    fun startRecognition(context: Context) {
      val appContext = context.applicationContext
      mainHandler.post {
        val service = connectedService
        if (service != null && service.packageName == appContext.packageName) service.beginAttempt()
        else bridgeState.value = YtmBridgeState(
          YtmBridgePhase.FAILED, "Enable the YouTube Music bridge in Accessibility settings first.",
        )
      }
    }

    fun cancelRecognition(context: Context) {
      val appContext = context.applicationContext
      mainHandler.post {
        val service = connectedService
        if (service != null && service.packageName == appContext.packageName) {
          service.stopAttempt(YtmBridgeState(message = "YouTube Music test stopped."), false)
        }
      }
    }
  }
}

private data class AutomaticBridgeAttempt(
  val token: ClockSessionToken,
  val continuation: CancellableContinuation<RecognizedSong?>,
)

private data class YtmNode(
  val text: String,
  val description: String,
  val className: String,
  val clickable: Boolean,
  val bounds: Rect,
  val boundsInWindow: Rect,
  val clickableAncestor: Rect?,
)

private data class YtmScreen(val nodes: List<YtmNode>, val rootBounds: Rect, val rootBoundsInWindow: Rect)
private enum class YtmKnownNavigationLabel { HOME, SEARCH, LIBRARY, SAMPLES, SOUND_SEARCH, SEARCH_ANOTHER_SONG }
private enum class YtmNavigationClass { TEXT, IMAGE, IMAGE_BUTTON, EDIT_TEXT, VIEW, VIEW_GROUP, OTHER }
private data class YtmKnownNavigationControl(
  val label: YtmKnownNavigationLabel,
  val viewClass: YtmNavigationClass,
  val clickable: Boolean,
  val bounds: Rect,
  val clickableAncestor: Rect?,
)
private data class YtmNavigationDebug(
  val elapsedMillis: Long,
  val nodes: Int,
  val focused: Boolean,
  val active: Boolean,
  val searchControls: Int,
  val soundControls: Int,
  val result: Boolean,
  val searchClicks: Int,
  val requested: Boolean,
  val lastAction: YtmNavigationAction,
  val lastAccepted: Boolean?,
)
private class LeftYouTubeMusicException : Exception()
private class YtmLaunchException : IOException()
private enum class YtmDiagnosticStage {
  IDLE, OVERLAY_SETUP, WAITING_OVERLAY_FRAME, WAITING_AWAKE_CLOCK, REATTACH_OVERLAY, YTM_LAUNCH, NAVIGATING,
  LISTENING, RESULT_ANCHOR, SCREENSHOT, OCR, PARSING, WAITING_OWN_CLOCK, RETURN_SESSION_CHECK,
  RETURN_FOREGROUND_CHECK, RETURN_COVER_FRAME, RETURN_REQUEST, RETURN_FIRST_DRAW, RETURN_DRAWN, STOPPED,
}
private enum class YtmReturnDecision {
  NONE, DENIED_SESSION, DENIED_FOREGROUND, REQUEST_FAILED, WAITING_FIRST_DRAW, DRAWN,
  DEPARTED, TIMEOUT, SESSION_ENDED,
}
private enum class YtmReturnForeground { UNKNOWN, ALLOWED, OTHER_APP, NO_APPLICATION_WINDOW, ROOT_UNAVAILABLE, QUERY_FAILED }
private enum class YtmDebugOutcome { READING, PARSED, REJECTED }
private data class YtmDebugGeometry(
  val windowBounds: Rect,
  val fullWidth: Int,
  val fullHeight: Int,
  val cropRect: Rect,
  val rootBounds: Rect,
  val anchorBounds: Rect,
  val artworkBounds: Rect,
  val rootBoundsInWindow: Rect,
  val anchorBoundsInWindow: Rect,
  val artworkBoundsInWindow: Rect,
)
private data class YtmRecognitionDebug(
  val titleHint: String,
  val geometry: YtmDebugGeometry? = null,
  val lines: List<YtmOcrLine> = emptyList(),
  val outcome: YtmDebugOutcome = YtmDebugOutcome.READING,
)

/** An opaque, non-focusable window; YTM retains input focus and accessibility visibility below it. */
private class BridgeOverlay(context: Context, onCancel: () -> Unit) : FrameLayout(context) {
  private val clock = TextView(context)
  private val date = TextView(context)
  private val status = TextView(context)
  private val song = TextView(context)
  private val artist = TextView(context)
  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(event: MotionEvent): Boolean = true
    override fun onDoubleTap(event: MotionEvent): Boolean { onCancel(); return true }
  })

  init {
    setBackgroundColor(Color.BLACK)
    val padding = (32 * resources.displayMetrics.density).toInt()
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(padding, padding, padding, padding)
    }
    addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    fun label(view: TextView, size: Float, color: Int, dotMatrix: Boolean = false) {
      view.gravity = Gravity.CENTER
      view.textSize = size
      view.setTextColor(color)
      if (dotMatrix) view.typeface = SonosVisualStyle.typeface(context)
      content.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
    label(clock, 76f, Color.WHITE)
    label(date, 22f, Color.LTGRAY)
    content.addView(View(context), LinearLayout.LayoutParams(1, 0, 0.6f))
    label(TextView(context).apply { text = "YOUTUBE MUSIC TEST" }, 18f, SonosVisualStyle.secondaryTextColor, true)
    label(status, 26f, SonosVisualStyle.primaryTextColor, true)
    label(song, 34f, SonosVisualStyle.primaryTextColor, true)
    label(artist, 24f, SonosVisualStyle.secondaryTextColor, true)
    content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
    label(TextView(context).apply {
      text = "STOP TEST"
      setPadding(padding, padding / 2, padding, padding / 2)
      setOnClickListener { onCancel() }
    }, 22f, SonosVisualStyle.primaryTextColor, true)
    label(TextView(context).apply { text = "Double tap anywhere to exit" }, 16f, Color.GRAY)
    tick()
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    if (gestureDetector.onTouchEvent(event) && event.actionMasked == MotionEvent.ACTION_UP) return true
    super.dispatchTouchEvent(event)
    return true
  }

  fun tick() {
    val now = Date()
    clock.text = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss", now)
    date.text = DateFormat.format("EEE, MMM d", now)
  }

  fun render(value: YtmBridgeState) {
    status.text = value.message
    song.text = value.title
    artist.text = value.artist
  }
}
