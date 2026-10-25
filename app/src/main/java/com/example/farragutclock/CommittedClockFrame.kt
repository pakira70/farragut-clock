package com.example.farragutclock

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver

/** A submitted hardware frame followed by two display ticks; the caller owns its bounded timeout. */
internal class CommittedClockFrame(
  private val view: View,
  private val onReady: () -> Unit,
  private val onUnavailable: () -> Unit = {},
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var disposed = false
  @Volatile private var cancelRequested = false
  private var observer: ViewTreeObserver? = null
  private var commitCallback: Runnable? = null
  private var committed = false
  private val secondTick = Runnable {
    if (!disposed && !cancelRequested) {
      if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
        dispose()
        if (!cancelRequested) onReady()
      } else unavailable()
    }
  }
  private val firstTick = Runnable {
    if (!disposed && !cancelRequested) view.postOnAnimation(secondTick)
  }
  private val deliverCommit = Runnable {
    if (!disposed && !cancelRequested) {
      if (view.isAttachedToWindow) view.postOnAnimation(firstTick) else unavailable()
    }
  }
  private val attachListener = object : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(view: View) = registerCommit()
    override fun onViewDetachedFromWindow(view: View) = unavailable()
  }

  init {
    view.addOnAttachStateChangeListener(attachListener)
    if (view.isAttachedToWindow) registerCommit()
  }

  fun cancel() {
    cancelRequested = true
    if (Looper.myLooper() == Looper.getMainLooper()) dispose()
    else mainHandler.post { dispose() }
  }

  private fun registerCommit() {
    if (disposed || cancelRequested || commitCallback != null) return
    // The YouTube Music bridge requires Android 14; unsupported/software windows fail closed.
    if (Build.VERSION.SDK_INT < 29 || !view.isHardwareAccelerated) {
      unavailable()
      return
    }
    val callback = Runnable {
      if (!disposed && !cancelRequested && !committed) {
        committed = true
        mainHandler.post(deliverCommit)
      }
    }
    observer = view.viewTreeObserver
    commitCallback = callback
    observer?.registerFrameCommitCallback(callback)
    view.invalidate()
  }

  private fun unavailable() {
    if (disposed) return
    dispose()
    if (!cancelRequested) onUnavailable()
  }

  private fun dispose() {
    if (disposed) return
    disposed = true
    view.removeOnAttachStateChangeListener(attachListener)
    if (Build.VERSION.SDK_INT >= 29) {
      val registeredObserver = observer
      val callback = commitCallback
      if (registeredObserver?.isAlive == true && callback != null) {
        registeredObserver.unregisterFrameCommitCallback(callback)
      }
    }
    mainHandler.removeCallbacks(deliverCommit)
    view.removeCallbacks(firstTick)
    view.removeCallbacks(secondTick)
    observer = null
    commitCallback = null
  }
}
