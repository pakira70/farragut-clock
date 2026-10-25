package com.example.farragutclock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the original centered clock layout and fits compact content into its half of the screen. */
internal class FittedDreamFrame(context: Context) : FrameLayout(context) {
  var fitContent: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      requestLayout()
    }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    if (!fitContent || childCount == 0) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      return
    }
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    getChildAt(0).measure(
      MeasureSpec.makeMeasureSpec(max(0, width - paddingLeft - paddingRight), MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
    )
    setMeasuredDimension(width, height)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    if (childCount == 0) return
    if (!fitContent) {
      getChildAt(0).apply { scaleX = 1f; scaleY = 1f }
      super.onLayout(changed, left, top, right, bottom)
      return
    }
    val child = getChildAt(0)
    val availableWidth = max(0, width - paddingLeft - paddingRight)
    val availableHeight = max(0, height - paddingTop - paddingBottom)
    val scale = min(1f, min(
      availableWidth.toFloat() / child.measuredWidth.coerceAtLeast(1),
      availableHeight.toFloat() / child.measuredHeight.coerceAtLeast(1),
    ))
    val x = paddingLeft + (availableWidth - child.measuredWidth) / 2
    val y = paddingTop + (availableHeight - child.measuredHeight) / 2
    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
    child.pivotX = child.measuredWidth / 2f
    child.pivotY = child.measuredHeight / 2f
    child.scaleX = scale
    child.scaleY = scale
  }
}

internal class SonosDreamLayout(context: Context) : ViewGroup(context) {
  val clockFrame = FittedDreamFrame(context)
  val musicFrame = FittedDreamFrame(context).apply {
    fitContent = true
    setPadding(dp(24), dp(12), dp(24), dp(24))
    visibility = GONE
  }
  val ticker = SongTickerView(context).apply { visibility = GONE }
  var split: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      clockFrame.fitContent = value
      val padding = if (value) dp(16) else 0
      clockFrame.setPadding(padding, padding, padding, padding)
      requestLayout()
    }

  init {
    setBackgroundColor(DreamVisualStyle.backgroundColor)
    addView(clockFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(musicFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(ticker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    setMeasuredDimension(width, height)
    val tickerHeight = min(dp(40), height)
    val clockHeight = when {
      split -> height / 2
      ticker.visibility == VISIBLE -> max(0, tickerTop(height, tickerHeight) - dp(8))
      else -> height
    }
    clockFrame.measure(exact(width), exact(clockHeight))
    musicFrame.measure(exact(width), exact(height - height / 2))
    ticker.measure(exact(max(0, width - dp(32))), exact(tickerHeight))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    clockFrame.layout(0, 0, width, clockFrame.measuredHeight)
    musicFrame.layout(0, height / 2, width, height)
    val tickerTop = tickerTop(height, ticker.measuredHeight)
    ticker.layout(dp(16), tickerTop, width - dp(16), tickerTop + ticker.measuredHeight)
  }

  fun stopTicker() { ticker.active = false }

  private fun tickerTop(availableHeight: Int, tickerHeight: Int): Int {
    val metrics = resources.displayMetrics
    val verticalDpi = metrics.ydpi.takeIf { it.isFinite() && it > 0f }
      ?: metrics.densityDpi.toFloat()
    // Center the lettering about 1.75 cm above the physical bottom of the screen.
    val centerInset = (17.5f / 25.4f * verticalDpi).roundToInt()
    return (availableHeight - centerInset - tickerHeight / 2)
      .coerceIn(0, max(0, availableHeight - tickerHeight))
  }

  private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
  private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

/** Slow right-to-left scrolling, independent of the clock's burn-in movement. */
internal class SongTickerView(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = SonosVisualStyle.secondaryTextColor
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
    typeface = SonosVisualStyle.typeface(context)
  }
  private var animator: ValueAnimator? = null
  private var progress = 0f
  var songText: String = ""
    set(value) {
      if (field == value) return
      field = value
      contentDescription = value
      restart(resetProgress = true)
    }
  var active: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      restart(resetProgress = true)
    }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val textWidth = paint.measureText(songText)
    val x = width - (width + textWidth) * progress
    val y = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(songText, x, y, paint)
  }

  override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
  override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { restart() }
  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    // This can run during View construction, before the paint has been initialized.
    if (visibility != VISIBLE) { animator?.cancel(); animator = null }
    else if (active) restart()
  }

  private fun restart(resetProgress: Boolean = false) {
    animator?.cancel()
    animator = null
    if (resetProgress) progress = 0f
    if (!active || !isAttachedToWindow || windowVisibility != VISIBLE || width == 0 || songText.isEmpty()) {
      invalidate()
      return
    }
    val cycleDuration = max(30_000L, ((width + paint.measureText(songText)) /
      (18f * resources.displayMetrics.density) * 1000).toLong())
    val resumeAt = (cycleDuration * progress.coerceIn(0f, 1f)).toLong()
    animator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = cycleDuration
      interpolator = LinearInterpolator()
      repeatCount = ValueAnimator.INFINITE
      addUpdateListener { progress = it.animatedValue as Float; invalidate() }
      start()
      currentPlayTime = resumeAt
    }
  }
}
