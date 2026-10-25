package com.example.farragutclock

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Visible clock host after YTM recognition has ended the system-managed Dream window. */
class ClockActivity : ComponentActivity() {
  private var sessionToken: ClockSessionToken? = null
  private lateinit var container: FrameLayout
  private var frameWait: CommittedClockFrame? = null
  private var settleFrameWait: CommittedClockFrame? = null
  private var settleTask: Runnable? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var intentGeneration = 0L
  private var readinessGeneration = 0L
  private var committedGeneration = -1L
  private var deliveredGeneration = -1L
  private var resumed = false
  private var windowFocused = false
  private var holdSystemBars = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        sessionToken?.let(ClockSession::exitToHome) ?: finishAndRemoveTask()
      }
    })
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    setContentView(container)
    attachClock(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    attachClock(intent)
  }

  private fun attachClock(intent: Intent) {
    val id = intent.getLongExtra(SESSION_ID_EXTRA, -1L)
    val token = ClockSessionToken(id)
    val existing = sessionToken
    if (existing != null && ClockSession.isActive(existing) && token != existing &&
      !ClockSession.isActive(token)) return
    cancelReadySettle()
    frameWait?.cancel()
    frameWait = null
    val generation = ++intentGeneration
    committedGeneration = -1L
    holdSystemBars = intent.getBooleanExtra(HOLD_SYSTEM_BARS_EXTRA, false)
    if (!ClockSession.attachActivity(token, this, container, ::applyBrightness)) {
      finishAndRemoveTask()
      return
    }
    sessionToken = token
    frameWait = CommittedClockFrame(container, onReady = {
      if (generation == intentGeneration && sessionToken == token && ClockSession.isActive(token)) {
        committedGeneration = generation
        notifyReadyClock()
      }
    })
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    if (windowFocused) hideSystemBars()
    notifyReadyClock()
  }

  override fun onPause() {
    resumed = false
    cancelReadySettle()
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    windowFocused = hasFocus
    if (hasFocus && resumed) hideSystemBars()
    if (!hasFocus) cancelReadySettle()
    notifyReadyClock()
  }

  internal fun isAwakeClockHost(): Boolean = resumed && windowFocused && !isFinishing && !isDestroyed

  internal fun isClockInteractiveHost(): Boolean = isAwakeClockHost() &&
    committedGeneration == intentGeneration && sessionToken?.let(ClockSession::isActive) == true

  internal fun requestReadyClockNotification(holdSystemBars: Boolean) {
    cancelReadySettle()
    this.holdSystemBars = holdSystemBars
    deliveredGeneration = -1L
    notifyReadyClock()
  }

  private fun notifyReadyClock() {
    val token = sessionToken ?: return
    val generation = intentGeneration
    if (!canNotifyReady(token, generation)) return
    if (holdSystemBars) {
      if (settleTask != null || settleFrameWait != null) return
      val readyGeneration = readinessGeneration
      // SystemUI can force the privacy chip's status bar visible without reporting it in app insets.
      // Keep the existing cover through the measured transient animation, then commit a clean frame.
      val task = Runnable {
        settleTask = null
        if (readyGeneration != readinessGeneration || !canNotifyReady(token, generation)) return@Runnable
        hideSystemBars()
        settleFrameWait = CommittedClockFrame(container, onReady = {
          settleFrameWait = null
          if (readyGeneration == readinessGeneration && canNotifyReady(token, generation)) {
            deliverReadyClock(token, generation)
          }
        })
      }
      settleTask = task
      mainHandler.postDelayed(task, SYSTEM_BARS_SETTLE_MILLIS)
      return
    }
    deliverReadyClock(token, generation)
  }

  private fun canNotifyReady(token: ClockSessionToken, generation: Long): Boolean =
    sessionToken == token && generation == intentGeneration && ClockSession.isClockInteractive(token) &&
      deliveredGeneration != generation

  private fun deliverReadyClock(token: ClockSessionToken, generation: Long) {
    deliveredGeneration = generation
    ClockSession.activityDrawn(token, this)
  }

  private fun cancelReadySettle() {
    readinessGeneration++
    settleTask?.let(mainHandler::removeCallbacks)
    settleTask = null
    settleFrameWait?.cancel()
    settleFrameWait = null
  }

  private fun hideSystemBars() {
    if (Build.VERSION.SDK_INT >= 30) {
      window.insetsController?.apply {
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsets.Type.systemBars())
      }
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    sessionToken?.let { ClockSession.configurationChanged(it, newConfig) }
  }

  override fun onStop() {
    sessionToken?.let { ClockSession.detachActivity(it, this) }
    super.onStop()
  }

  override fun onDestroy() {
    intentGeneration++
    cancelReadySettle()
    frameWait?.cancel()
    frameWait = null
    sessionToken?.let { ClockSession.detachActivity(it, this, destroyed = true) }
    super.onDestroy()
  }

  private fun applyBrightness(brightness: Float) {
    window.attributes = window.attributes.apply { screenBrightness = brightness }
  }

  internal companion object {
    const val SESSION_ID_EXTRA = "clock_session_id"
    const val HOLD_SYSTEM_BARS_EXTRA = "hold_system_bars"
    private const val SYSTEM_BARS_SETTLE_MILLIS = 2_000L
  }
}
