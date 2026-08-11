package com.example.farragutclock

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal object DreamVisualStyle {
  val backgroundColor: Int = Color.BLACK
  val primaryTextColor: Int = Color.rgb(232, 232, 228)
  val secondaryTextColor: Int = Color.rgb(174, 174, 170)
  const val clockTextSizeSp: Float = 128f
  const val dateTextSizeSp: Float = 28f
  const val weatherTextSizeSp: Float = 22f
  const val fontFamily: String = "sans-serif"
  const val dateTopMarginDp: Int = 8
  const val weatherTopMarginDp: Int = 14
}

class FarragutDreamService : DreamService() {
  private val handler = Handler(Looper.getMainLooper())
  private val weatherRepository by lazy { WeatherRepository(applicationContext) }
  private lateinit var clockView: TextView
  private lateinit var dateView: TextView
  private lateinit var weatherView: TextView
  private var dreamScope: CoroutineScope? = null
  private var use24HourTime: Boolean = false

  private val clockUpdater =
    object : Runnable {
      override fun run() {
        val nowDate = Date()
        clockView.text =
          SimpleDateFormat(
              if (use24HourTime) TIME_24_HOUR_PATTERN else TIME_12_HOUR_PATTERN,
              Locale.getDefault(),
            )
            .format(nowDate)
        dateView.text =
          SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault()).format(nowDate)

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
    use24HourTime = ClockPreferences.uses24HourTime(this)
    handler.removeCallbacks(clockUpdater)
    clockUpdater.run()
    startWeatherUpdates()
  }

  override fun onDreamingStopped() {
    handler.removeCallbacks(clockUpdater)
    stopWeatherUpdates()
    super.onDreamingStopped()
  }

  override fun onDetachedFromWindow() {
    handler.removeCallbacks(clockUpdater)
    stopWeatherUpdates()
    super.onDetachedFromWindow()
  }

  private fun createDreamView(): View {
    clockView =
      TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(DreamVisualStyle.primaryTextColor)
        textSize = DreamVisualStyle.clockTextSizeSp
        typeface = dreamTypeface()
      }

    dateView = secondaryTextView(DreamVisualStyle.dateTextSizeSp)
    weatherView =
      secondaryTextView(DreamVisualStyle.weatherTextSizeSp).apply {
        visibility = View.GONE
      }

    val content =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(
          clockView,
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          ),
        )
        addView(
          dateView,
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = DreamVisualStyle.dateTopMarginDp.toPixels() },
        )
        addView(
          weatherView,
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = DreamVisualStyle.weatherTopMarginDp.toPixels() },
        )
      }

    return FrameLayout(this).apply {
      setBackgroundColor(DreamVisualStyle.backgroundColor)
      addView(
        content,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
          Gravity.CENTER,
        ),
      )
    }
  }

  private fun secondaryTextView(sizeSp: Float): TextView =
    TextView(this).apply {
      gravity = Gravity.CENTER
      setTextColor(DreamVisualStyle.secondaryTextColor)
      textSize = sizeSp
      typeface = dreamTypeface()
      maxLines = 1
    }

  private fun dreamTypeface(): Typeface =
    Typeface.create(DreamVisualStyle.fontFamily, Typeface.NORMAL)

  private fun startWeatherUpdates() {
    stopWeatherUpdates()

    weatherRepository.cachedWeather()?.let(::showWeather)
    dreamScope =
      CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
        scope.launch {
          val cachedWeather = weatherRepository.cachedWeather()
          if (cachedWeather == null || weatherRepository.isStale(cachedWeather)) {
            refreshWeather()
          }

          while (isActive) {
            delay(WeatherRepository.CACHE_MAX_AGE_MILLIS)
            refreshWeather()
          }
        }
      }
  }

  private fun stopWeatherUpdates() {
    dreamScope?.cancel()
    dreamScope = null
  }

  private suspend fun refreshWeather() {
    try {
      showWeather(weatherRepository.refreshWeather())
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: Exception) {
      // Keep showing the last successful result, or no weather line at all.
    }
  }

  private fun showWeather(weather: WeatherSnapshot) {
    weatherView.text =
      "${weather.temperatureF.roundToInt()}°  ${weatherCondition(weather.weatherCode)}" +
      "  ·  H ${weather.highF.roundToInt()}°  L ${weather.lowF.roundToInt()}°"
    weatherView.visibility = View.VISIBLE
  }

  private fun Int.toPixels(): Int = (this * resources.displayMetrics.density).roundToInt()

  private companion object {
    const val MILLIS_PER_MINUTE = 60_000L
    const val TIME_12_HOUR_PATTERN = "h:mm a"
    const val TIME_24_HOUR_PATTERN = "HH:mm"
    const val DATE_FORMAT_PATTERN = "EEEE, MMMM d"
  }
}
