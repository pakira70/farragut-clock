package com.example.farragutclock

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Exercises real Android measuring and transformed bounds without starting a screen saver. */
class SonosDreamLayoutTest {
  @Test
  fun tallSplitClockFitsInsideUpperHalfInPortraitAndLandscape() = onMainThread { context ->
    listOf(480 to 800, 800 to 480).forEach { (widthDp, heightDp) ->
      val scene = SonosDreamLayout(context)
      val clock = addTallClock(context, scene)
      scene.split = true
      scene.musicFrame.visibility = View.VISIBLE

      measureAndLayout(scene, context.dp(widthDp), context.dp(heightDp))

      val clockBounds = boundsInScene(clock, scene.clockFrame)
      assertEquals(scene.height / 2, scene.clockFrame.height)
      assertTrue("Tall content should shrink in split mode", clock.scaleY < 1f)
      assertTrue("Clock should fit below the upper padding", clockBounds.top >= -1f)
      assertTrue("Clock should stay in the upper half", clockBounds.bottom <= scene.height / 2f + 1f)
      assertTrue("Clock should fit horizontally", clockBounds.left >= -1f)
      assertTrue("Clock should fit horizontally", clockBounds.right <= scene.width + 1f)
    }
  }

  @Test
  fun fullPlaybackReservesFooterAndFitsTallClockAboveTicker() = onMainThread { context ->
    val scene = SonosDreamLayout(context)
    val clock = addTallClock(context, scene)
    scene.clockFrame.fitContent = true
    scene.ticker.visibility = View.VISIBLE

    measureAndLayout(scene, context.dp(800), context.dp(480))

    val clockBounds = boundsInScene(clock, scene.clockFrame)
    assertTrue("Clock frame should reserve the ticker footer", scene.clockFrame.bottom < scene.ticker.top)
    assertTrue("Scaled clock should remain above the ticker", clockBounds.bottom <= scene.ticker.top + 1f)
    assertTrue("Tall full-mode content should still be readable on screen", clockBounds.top >= -1f)
    assertTrue("Tall full-mode content should shrink to fit", clock.scaleY < 1f)
  }

  @Test
  fun idleClockKeepsEntireScreenAndOriginalScale() = onMainThread { context ->
    val scene = SonosDreamLayout(context)
    val clock = addTallClock(context, scene)

    measureAndLayout(scene, context.dp(480), context.dp(800))

    assertEquals(View.GONE, scene.ticker.visibility)
    assertEquals(scene.height, scene.clockFrame.height)
    assertEquals(1f, clock.scaleX, 0f)
    assertEquals(1f, clock.scaleY, 0f)
  }

  @Test
  fun leavingSplitModeRestoresFullClockScaleAndAvailableHeight() = onMainThread { context ->
    val scene = SonosDreamLayout(context)
    val clock = addTallClock(context, scene)
    val width = context.dp(480)
    val height = context.dp(800)
    measureAndLayout(scene, width, height)
    val originalHeight = clock.measuredHeight

    scene.split = true
    scene.musicFrame.visibility = View.VISIBLE
    measureAndLayout(scene, width, height)
    assertTrue(clock.scaleY < 1f)

    scene.split = false
    scene.musicFrame.visibility = View.GONE
    measureAndLayout(scene, width, height)

    assertEquals(height, scene.clockFrame.height)
    assertEquals(originalHeight, clock.measuredHeight)
    assertEquals(1f, clock.scaleX, 0f)
    assertEquals(1f, clock.scaleY, 0f)
    assertEquals(0, scene.clockFrame.paddingTop)
  }

  private fun addTallClock(context: Context, scene: SonosDreamLayout): View {
    val clock = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      // Model unusually tall clock/transit content independently of the device font scale.
      minimumHeight = context.dp(720)
      addView(TextView(context).apply { text = "12:34"; textSize = 104f })
      repeat(4) { index ->
        addView(TextView(context).apply { text = "${index + 1} min  →  Downtown"; textSize = 24f })
      }
    }
    scene.clockFrame.addView(
      clock,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
      ),
    )
    return clock
  }

  private fun boundsInScene(child: View, frame: View): RectF =
    RectF(0f, 0f, child.width.toFloat(), child.height.toFloat()).also { bounds ->
      child.matrix.mapRect(bounds)
      bounds.offset((child.left + frame.left).toFloat(), (child.top + frame.top).toFloat())
    }

  private fun measureAndLayout(view: View, width: Int, height: Int) {
    view.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, width, height)
  }

  private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private fun onMainThread(block: (Context) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync { block(instrumentation.targetContext) }
  }
}
