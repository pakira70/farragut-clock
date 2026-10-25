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

class SonosPodcastLayoutTest {
  @Test
  fun podcastSeriesAppearsBelowRoomAndPublisherIsNotRepeated() = onMainThread { context ->
    val playback = SonosPlayback(
      listOf("Kitchen"),
      SonosTrack("An episode title", "Example Podcast", podcastSeries = "Example Podcast"),
    )
    val group = sonosPlaybackGroupView(context, playback, false, "")
    val lines = (0 until group.childCount).map { (group.getChildAt(it) as TextView).text.toString() }
    assertEquals(listOf("KITCHEN", "EXAMPLE PODCAST", "AN EPISODE TITLE"), lines)
    assertEquals("An episode title — Example Podcast", sonosTrackSummary(playback.current))

    val radio = sonosPlaybackGroupView(context, SonosPlayback(
      listOf("Kitchen"), SonosTrack("Afternoon Show", "KEXP", album = "Station information"),
    ), false, "")
    assertEquals(3, radio.childCount)
    assertEquals("AFTERNOON SHOW", (radio.getChildAt(1) as TextView).text.toString())
    assertEquals("Same — Same", sonosTrackSummary(SonosTrack("Same", "Same")))

    val pocketCasts = SonosTrack("In Moscow's Shadows 268: Peace and (Info) War",
      podcastSeries = "In Moscow's Shadows")
    val actual = sonosPlaybackGroupView(context, SonosPlayback(listOf("Living Room"), pocketCasts), false, "")
    assertEquals("268: PEACE AND (INFO) WAR", (actual.getChildAt(2) as TextView).text.toString())
    assertEquals("268: Peace and (Info) War — In Moscow's Shadows", sonosTrackSummary(pocketCasts))
  }

  @Test
  fun longPodcastSeriesAndEpisodesFitInBothOrientationsWithTwoGroups() = onMainThread { context ->
    listOf(480 to 800, 800 to 480).forEach { (widthDp, heightDp) ->
      val scene = SonosDreamLayout(context).apply {
        split = true
        musicFrame.visibility = View.VISIBLE
      }
      val groups = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
      repeat(2) { index ->
        groups.addView(sonosPlaybackGroupView(context, SonosPlayback(
          listOf(if (index == 0) "Kitchen" else "Living Room"),
          SonosTrack(
            "An unusually long episode title that spans the complete width of the song display",
            "A podcast publisher",
            podcastSeries = "A long podcast series name with several words to fit on two lines",
          ),
        ), true, ""), LinearLayout.LayoutParams(-1, -2).apply {
          if (index > 0) topMargin = context.dp(20)
        })
      }
      scene.musicFrame.addView(groups, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
      val width = context.dp(widthDp)
      val height = context.dp(heightDp)
      scene.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
      scene.layout(0, 0, width, height)
      val bounds = RectF(0f, 0f, groups.width.toFloat(), groups.height.toFloat())
      groups.matrix.mapRect(bounds)
      bounds.offset(groups.left.toFloat(), groups.top.toFloat())
      assertTrue("Podcast content was measured", groups.measuredHeight > 0 && groups.measuredWidth > 0)
      assertTrue("Podcast groups fit above bottom padding",
        bounds.bottom <= scene.musicFrame.height - scene.musicFrame.paddingBottom + 1f)
      assertTrue("Podcast groups fit below top padding", bounds.top >= scene.musicFrame.paddingTop - 1f)
      assertTrue("Podcast groups fit horizontally", bounds.left >= scene.musicFrame.paddingLeft - 1f &&
        bounds.right <= width - scene.musicFrame.paddingRight + 1f)
      repeat(groups.childCount) { groupIndex ->
        val group = groups.getChildAt(groupIndex) as LinearLayout
        listOf(1, 2).forEach { lineIndex ->
          val textView = group.getChildAt(lineIndex) as TextView
          val layout = textView.layout
          assertTrue("Full podcast text is laid out", layout.lineCount > 0)
          assertEquals("No episode or series text is omitted", textView.text.length,
            layout.getLineEnd(layout.lineCount - 1))
          repeat(layout.lineCount) { line ->
            assertEquals("Podcast rows must not ellipsize", 0, layout.getEllipsisCount(line))
          }
        }
      }
    }
  }

  private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private fun onMainThread(block: (Context) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync { block(instrumentation.targetContext) }
  }
}
