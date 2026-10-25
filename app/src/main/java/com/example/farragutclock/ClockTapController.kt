package com.example.farragutclock

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/** Confirms a single tap only after it can no longer be the first half of a double tap. */
internal class ClockTapController(
  private val view: View,
  onSingleTap: () -> Unit,
  onDoubleTap: () -> Unit,
) {
  private val detector = GestureDetector(
    view.context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(event: MotionEvent): Boolean = true

      override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        view.performClick()
        return true
      }

      override fun onDoubleTap(event: MotionEvent): Boolean {
        onDoubleTap()
        return true
      }

      override fun onDoubleTapEvent(event: MotionEvent): Boolean = true
    },
    Handler(Looper.getMainLooper()),
  )

  init {
    // Accessibility click actions use the same callback as a confirmed physical single tap.
    view.setOnClickListener { onSingleTap() }
    installTouchListener()
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installTouchListener() {
    view.setOnTouchListener { _, event ->
      detector.onTouchEvent(event)
      // Consume every event. Falling through on UP would let View fire an immediate click
      // before GestureDetector can distinguish a single tap from a double tap.
      true
    }
  }

  /** Drop pending single-tap callbacks when the dream stops, without removing its listeners. */
  fun cancel() {
    val now = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    try {
      detector.onTouchEvent(event)
    } finally {
      event.recycle()
    }
  }
}
