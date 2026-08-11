package com.example.farragutclock

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Date

internal object DreamVisualStyle {
  val backgroundColor: Int = Color.BLACK
  val primaryTextColor: Int = Color.rgb(232, 232, 228)
  val secondaryTextColor: Int = Color.rgb(174, 174, 170)
  const val clockTextSizeSp: Float = 128f
  const val dateTextSizeSp: Float = 28f
  const val fontFamily: String = "sans-serif"
}

class FarragutDreamService : DreamService() {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var clockView: TextView

  private val clockUpdater =
    object : Runnable {
      override fun run() {
        clockView.text = DateFormat.getTimeFormat(this@FarragutDreamService).format(Date())

        val now = System.currentTimeMillis()
        handler.postDelayed(this, MILLIS_PER_MINUTE - (now % MILLIS_PER_MINUTE))
      }
    }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    isInteractive = false
    isFullscreen = true
    isScreenBright = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    setContentView(createDreamView())
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    handler.removeCallbacks(clockUpdater)
    clockUpdater.run()
  }

  override fun onDreamingStopped() {
    handler.removeCallbacks(clockUpdater)
    super.onDreamingStopped()
  }

  override fun onDetachedFromWindow() {
    handler.removeCallbacks(clockUpdater)
    super.onDetachedFromWindow()
  }

  private fun createDreamView(): View {
    clockView =
      TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(DreamVisualStyle.primaryTextColor)
        textSize = DreamVisualStyle.clockTextSizeSp
        typeface = Typeface.create(DreamVisualStyle.fontFamily, Typeface.NORMAL)
      }

    return FrameLayout(this).apply {
      setBackgroundColor(DreamVisualStyle.backgroundColor)
      addView(
        clockView,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT,
        ),
      )
    }
  }

  private companion object {
    const val MILLIS_PER_MINUTE = 60_000L
  }
}
