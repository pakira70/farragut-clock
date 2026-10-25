package com.example.farragutclock

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Runs the real Android gesture detector, including its delayed single-tap confirmation. */
class ClockTapControllerTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val singleTaps = AtomicInteger()
  private val doubleTaps = AtomicInteger()
  private lateinit var view: View
  private lateinit var controller: ClockTapController

  @Before
  fun createView() {
    instrumentation.runOnMainSync {
      view = View(instrumentation.targetContext).apply { layout(0, 0, 400, 400) }
      controller = ClockTapController(
        view,
        onSingleTap = { singleTaps.incrementAndGet() },
        onDoubleTap = { doubleTaps.incrementAndGet() },
      )
    }
  }

  @After
  fun clearPendingCallbacks() {
    instrumentation.runOnMainSync { controller.cancel() }
  }

  @Test
  fun singleTapWaitsForConfirmationThenClicksOnce() {
    tap()
    assertEquals("A possible double tap must not toggle the clock yet", 0, singleTaps.get())
    assertEquals(0, doubleTaps.get())

    waitForGestureDecision()

    assertEquals(1, singleTaps.get())
    assertEquals(0, doubleTaps.get())
  }

  @Test
  fun doubleTapExitsOnceWithoutAlsoTogglingTheClock() {
    tap()
    // A realistic second tap: beyond the minimum interval, within the double-tap window.
    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() / 3)
    tap()

    assertEquals(0, singleTaps.get())
    assertEquals(1, doubleTaps.get())
    waitForGestureDecision()
    assertEquals("The first tap must not fire after the double tap", 0, singleTaps.get())
    assertEquals(1, doubleTaps.get())
  }

  @Test
  fun cancelDropsPendingTapAndAllowsTheNextGesture() {
    tap()
    instrumentation.runOnMainSync { controller.cancel() }
    waitForGestureDecision()

    assertEquals(0, singleTaps.get())
    assertEquals(0, doubleTaps.get())

    tap()
    waitForGestureDecision()
    assertEquals(1, singleTaps.get())
    assertEquals(0, doubleTaps.get())
  }

  @Test
  fun accessibilityClickUsesTheSingleTapAction() {
    instrumentation.runOnMainSync { view.performClick() }

    assertEquals(1, singleTaps.get())
    assertEquals(0, doubleTaps.get())
    waitForGestureDecision()
    assertEquals(1, singleTaps.get())
  }

  private fun tap() {
    instrumentation.runOnMainSync {
      val downTime = SystemClock.uptimeMillis()
      dispatch(downTime, downTime, MotionEvent.ACTION_DOWN)
      dispatch(downTime, downTime + 1, MotionEvent.ACTION_UP)
    }
  }

  private fun dispatch(downTime: Long, eventTime: Long, action: Int) {
    val event = MotionEvent.obtain(downTime, eventTime, action, 120f, 120f, 0)
    try {
      view.dispatchTouchEvent(event)
    } finally {
      event.recycle()
    }
  }

  private fun waitForGestureDecision() {
    // Leave the main looper free to deliver GestureDetector's confirmation message.
    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100L)
    instrumentation.waitForIdleSync()
  }
}
