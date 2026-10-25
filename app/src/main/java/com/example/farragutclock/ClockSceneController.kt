package com.example.farragutclock

import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
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
  val transitLabelTextColor: Int = Color.rgb(204, 204, 200)
  val transitDestinationTextColor: Int = Color.rgb(158, 158, 154)
  val scheduledTransitTextColor: Int = Color.rgb(186, 158, 204)
  val scheduledTransitDestinationTextColor: Int = Color.rgb(164, 140, 184)
  const val clockTextSizeSp: Float = 104f
  const val dateTextSizeSp: Float = 32f
  const val weatherTextSizeSp: Float = 26f
  const val transitTextSizeSp: Float = 24f
  const val trolleyClockTextSizeSp: Float = 56f
  const val trolleyDateTextSizeSp: Float = 26f
  const val trolleyWeatherTextSizeSp: Float = 40f
  const val trolleyTransitTextSizeSp: Float = 34f
  const val fontFamily: String = "sans-serif"
  const val dateTopMarginDp: Int = 8
  const val weatherTopMarginDp: Int = 14
  const val aqiTopMarginDp: Int = 6
  const val transitTopMarginDp: Int = 24
  const val transitRowSpacingDp: Int = 6
  const val transitLabelWidthDp: Int = 56
  const val transitRouteSpacingDp: Int = 40
}

internal class ClockSceneController(
  context: Context,
  private val applyHostBrightness: (Float) -> Unit,
  private val onExitClock: () -> Unit,
  private val beginMicrophoneSession: () -> Boolean,
  private val endMicrophoneSession: () -> Unit,
) : ContextWrapper(context.applicationContext) {
  private val handler = Handler(Looper.getMainLooper())
  private val weatherRepository by lazy { WeatherRepository(applicationContext) }
  private val transitRepository by lazy { TransitRepository(applicationContext) }
  private val sonosRepository by lazy { SonosRepository(applicationContext) }
  private val sonosState = SonosDisplayState()
  private lateinit var sceneView: SonosDreamLayout
  private lateinit var musicContainer: LinearLayout
  private var tapController: ClockTapController? = null
  private var displayedPlayback: List<SonosPlayback> = emptyList()
  private var displayedRecognitionStatus = ""
  private lateinit var clockView: TextView
  private lateinit var dateView: TextView
  private lateinit var weatherView: TextView
  private lateinit var aqiView: TextView
  private lateinit var transitContainer: LinearLayout
  private lateinit var compositionView: LinearLayout
  private val transitRouteLabelViews = mutableMapOf<String, TextView>()
  private val transitArrivalViews = mutableMapOf<String, List<TextView>>()
  private val transitArrivalsByRoute = mutableMapOf<String, List<TransitArrival>>()
  private var lastTransitSuccessMillis: Long? = null
  private var dreamScope: CoroutineScope? = null
  private var recognitionMonitor: LineInRecognitionMonitor? = null
  private var running = false
  private var use24HourTime: Boolean = false
  private var useTrolleyDisplay: Boolean = false
  private var burnInPositionIndex: Int = 0

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
        applyNightBrightness()
        hideExpiredWeather(now)
        renderTransitArrivals(now)
        handler.postDelayed(this, MILLIS_PER_MINUTE - (now % MILLIS_PER_MINUTE))
      }
    }

  private val brightnessApplier = Runnable { applyNightBrightness() }

  val view: View get() = sceneView

  init {
    createDreamView()
    useTrolleyDisplay = ClockPreferences.usesTrolleyDisplay(this)
    applyDisplayMode()
  }

  fun start() {
    if (running) return
    running = true
    refreshHost()
    handler.removeCallbacks(clockUpdater)
    clockUpdater.run()
    startWeatherUpdates()
    startTransitUpdates()
    startRecognitionMonitoring()
    startSonosUpdates()
    startBurnInProtection()
  }

  fun stop() {
    if (!running) return
    running = false
    tapController?.cancel()
    handler.removeCallbacks(clockUpdater)
    handler.removeCallbacks(brightnessApplier)
    lastTransitSuccessMillis = null
    transitArrivalsByRoute.clear()
    stopWeatherUpdates()
    endMicrophoneSession()
  }

  fun refreshHost() {
    use24HourTime = ClockPreferences.uses24HourTime(this)
    useTrolleyDisplay = ClockPreferences.usesTrolleyDisplay(this)
    applyDisplayMode()
    handler.removeCallbacks(brightnessApplier)
    // Configure a new host before its first visible frame, including a not-yet-attached overlay.
    applyNightBrightness()
    handler.postDelayed(brightnessApplier, BRIGHTNESS_REAPPLY_DELAY_MILLIS)
  }

  fun onConfigurationChanged(newConfig: Configuration) {
    updateTransitLayout(newConfig.orientation)
    applySonosLayout()
    refreshHost()
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
    aqiView =
      secondaryTextView(DreamVisualStyle.weatherTextSizeSp).apply {
        visibility = View.GONE
      }
    transitContainer = createTransitContainer()

    compositionView =
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
        addView(
          aqiView,
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = DreamVisualStyle.aqiTopMarginDp.toPixels() },
        )
        addView(
          transitContainer,
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply { topMargin = DreamVisualStyle.transitTopMarginDp.toPixels() },
        )
      }

    musicContainer = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    sceneView = SonosDreamLayout(this).apply {
      clockFrame.addView(
        compositionView,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
          Gravity.CENTER,
        ),
      )
      musicFrame.addView(
        musicContainer,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
          Gravity.CENTER,
        ),
      )
      isFocusable = true
    }
    tapController = ClockTapController(
      sceneView,
      onSingleTap = {
        if (sonosState.isPlaying) {
          sonosState.toggle()
          applySonosLayout()
        }
      },
      onDoubleTap = onExitClock,
    )
    return sceneView
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

  private fun createTransitContainer(): LinearLayout {
    transitRouteLabelViews.clear()
    transitArrivalViews.clear()
    val useSideBySideLayout =
      resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    return LinearLayout(this).apply {
      orientation =
        if (useSideBySideLayout) {
          LinearLayout.HORIZONTAL
        } else {
          LinearLayout.VERTICAL
        }
      visibility = View.VISIBLE

      TransitConfig.stops.forEachIndexed { index, stop ->
        val routeLabel =
          TextView(this@ClockSceneController).apply {
            text = stop.displayRouteName
            setTextColor(DreamVisualStyle.transitLabelTextColor)
            textSize = DreamVisualStyle.transitTextSizeSp
            typeface = Typeface.create(DreamVisualStyle.fontFamily, Typeface.BOLD)
          }
        transitRouteLabelViews[stop.displayRouteName] = routeLabel
        val arrivalViews =
          List(MAX_DISPLAYED_ARRIVALS) {
            secondaryTextView(DreamVisualStyle.transitTextSizeSp).apply {
              text = NO_ARRIVALS_TEXT
            }
          }
        transitArrivalViews[stop.displayRouteName] = arrivalViews

        val arrivalsColumn =
          LinearLayout(this@ClockSceneController).apply {
            orientation = LinearLayout.VERTICAL
            arrivalViews.forEachIndexed { arrivalIndex, arrivalView ->
              addView(
                arrivalView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                  )
                  .apply {
                    if (arrivalIndex > 0) {
                      topMargin = DreamVisualStyle.transitRowSpacingDp.toPixels()
                    }
                  },
              )
            }
          }

        val routeGroup =
          LinearLayout(this@ClockSceneController).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(
              routeLabel,
              LinearLayout.LayoutParams(
                DreamVisualStyle.transitLabelWidthDp.toPixels(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
              ),
            )
            addView(
              arrivalsColumn,
              LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
              ),
            )
          }

        addView(
          routeGroup,
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply {
              if (index > 0) {
                if (useSideBySideLayout) {
                  marginStart = DreamVisualStyle.transitRouteSpacingDp.toPixels()
                } else {
                  topMargin = DreamVisualStyle.transitRouteSpacingDp.toPixels()
                }
              }
            },
        )
      }
    }
  }

  private fun updateTransitLayout(orientation: Int) {
    if (!::transitContainer.isInitialized) return

    val useSideBySideLayout = orientation == Configuration.ORIENTATION_LANDSCAPE
    transitContainer.orientation =
      if (useSideBySideLayout) {
        LinearLayout.HORIZONTAL
      } else {
        LinearLayout.VERTICAL
      }

    val routeSpacing = if (sonosState.isSplit) 16 else DreamVisualStyle.transitRouteSpacingDp
    for (index in 0 until transitContainer.childCount) {
      val routeGroup = transitContainer.getChildAt(index)
      val layoutParameters = routeGroup.layoutParams as LinearLayout.LayoutParams
      layoutParameters.marginStart =
        if (useSideBySideLayout && index > 0) {
          routeSpacing.toPixels()
        } else {
          0
        }
      layoutParameters.topMargin =
        if (!useSideBySideLayout && index > 0) {
          routeSpacing.toPixels()
        } else {
          0
        }
      routeGroup.layoutParams = layoutParameters
    }
    transitContainer.requestLayout()
  }

  private fun startWeatherUpdates() {
    stopWeatherUpdates()

    weatherRepository.cachedWeather()?.let(::showWeather)
    dreamScope =
      CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
        scope.launch {
          refreshWeather()

          while (isActive) {
            delay(WeatherRepository.CACHE_MAX_AGE_MILLIS)
            refreshWeather()
          }
        }
      }
  }

  private fun startTransitUpdates() {
    dreamScope?.launch {
      while (isActive) {
        refreshTransit()
        delay(TransitRepository.REFRESH_INTERVAL_MILLIS)
      }
    }
  }

  private fun startBurnInProtection() {
    resetBurnInPosition()
    dreamScope?.launch {
      while (isActive) {
        delay(BURN_IN_SHIFT_INTERVAL_MILLIS)
        burnInPositionIndex = (burnInPositionIndex + 1) % BURN_IN_OFFSETS_PIXELS.size
        val (offsetX, offsetY) = BURN_IN_OFFSETS_PIXELS[burnInPositionIndex]
        compositionView.translationX = offsetX.toFloat()
        compositionView.translationY = offsetY.toFloat()
        musicContainer.translationX = offsetX.toFloat()
        musicContainer.translationY = offsetY.toFloat()
        sceneView.ticker.translationY = offsetY.toFloat()
      }
    }
  }

  private fun stopWeatherUpdates() {
    recognitionMonitor?.close()
    recognitionMonitor = null
    dreamScope?.cancel()
    dreamScope = null
    sonosState.clear()
    displayedPlayback = emptyList()
    if (::sceneView.isInitialized) {
      sceneView.stopTicker()
      applySonosLayout()
    }
    resetBurnInPosition()
  }

  private fun startSonosUpdates() {
    dreamScope?.launch {
      var previousSelection = ClockPreferences.selectedSonosRoomIds(this@ClockSceneController)
      while (isActive) {
        val selection = ClockPreferences.selectedSonosRoomIds(this@ClockSceneController)
        if (selection != previousSelection) {
          recognitionMonitor?.updatePlayback(emptyList())
          sonosState.clear()
          applySonosLayout()
          previousSelection = selection
        }
        val playback = try {
          sonosRepository.refreshPlayback(selection)
        } catch (exception: CancellationException) {
          throw exception
        } catch (_: Exception) {
          emptyList()
        }
        recognitionMonitor?.updatePlayback(playback)
        sonosState.update(playback, SystemClock.elapsedRealtime())
        applySonosLayout()
        delay(SonosRepository.POLL_INTERVAL_MILLIS)
      }
    }
  }

  private fun applySonosLayout() {
    if (!::sceneView.isInitialized) return
    val splitChanged = sceneView.split != sonosState.isSplit
    sceneView.split = sonosState.isSplit
    sceneView.clockFrame.fitContent = sonosState.isPlaying
    sceneView.musicFrame.visibility = if (sonosState.isSplit) View.VISIBLE else View.GONE
    val showTicker = sonosState.isPlaying && !sonosState.isSplit
    sceneView.ticker.visibility = if (showTicker) View.VISIBLE else View.GONE
    val presentation = recognitionMonitor?.displayedPlayback(sonosState.playback) ?: sonosState.playback
    // Keep a confirmed song still while a periodic recognition check runs underneath it.
    val recognitionStatus = if (presentation.any {
        it.isLineInRecognitionEligible && it.current.artist.isBlank()
      }) recognitionMonitor?.status.orEmpty() else ""
    if (displayedPlayback != presentation || displayedRecognitionStatus != recognitionStatus) {
      displayedPlayback = presentation
      displayedRecognitionStatus = recognitionStatus
      renderSonosPlayback()
    }
    sceneView.ticker.active = showTicker
    if (splitChanged) {
      applyDisplayMode()
      updateTransitLayout(resources.configuration.orientation)
      weatherRepository.cachedWeather()?.let(::showWeather)
      renderTransitArrivals()
      setCompositionMargin(dateView, if (sonosState.isSplit) 4 else DreamVisualStyle.dateTopMarginDp)
      setCompositionMargin(weatherView, if (sonosState.isSplit) 6 else DreamVisualStyle.weatherTopMarginDp)
      setCompositionMargin(transitContainer, if (sonosState.isSplit) 10 else DreamVisualStyle.transitTopMarginDp)
    }
    sceneView.contentDescription = when {
      sonosState.isSplit -> "Clock and Sonos. Tap once for the full clock. Double-tap to exit."
      sonosState.isPlaying -> "Full clock. Tap once for Sonos. Double-tap to exit."
      else -> "Clock. Double-tap to exit."
    }
  }

  private fun startRecognitionMonitoring() {
    val scope = dreamScope ?: return
    recognitionMonitor = LineInRecognitionMonitor(
      applicationContext,
      scope,
      beginMicrophoneSession = beginMicrophoneSession,
      endMicrophoneSession = endMicrophoneSession,
      onChanged = { applySonosLayout() },
    ).also { it.start() }
  }

  private fun setCompositionMargin(view: View, marginDp: Int) {
    (view.layoutParams as LinearLayout.LayoutParams).also {
      it.topMargin = marginDp.toPixels()
      view.layoutParams = it
    }
  }

  private fun renderSonosPlayback() {
    musicContainer.removeAllViews()
    val multipleGroups = displayedPlayback.size > 1
    val songTypeface = SonosVisualStyle.typeface(this)
    displayedPlayback.forEachIndexed { index, playback ->
      val group = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
      }
      fun addLine(text: String, size: Float, primary: Boolean = false, lines: Int = 1) {
        if (text.isBlank()) return
        group.addView(secondaryTextView(size).apply {
          this.text = text.uppercase(Locale.ROOT)
          typeface = songTypeface
          setTextColor(if (primary) SonosVisualStyle.primaryTextColor else SonosVisualStyle.secondaryTextColor)
          includeFontPadding = false
          maxLines = lines
          ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 6.toPixels() })
      }
      addLine(playback.roomNames.joinToString(" · "), 16f)
      addLine(playback.current.title, if (multipleGroups) 34f else 48f, primary = true, lines = 2)
      addLine(playback.current.artist, if (multipleGroups) 22f else 26f)
      if (playback.isLineInRecognitionEligible && displayedRecognitionStatus.isNotBlank()) {
        addLine(displayedRecognitionStatus, 14f, lines = 2)
      }
      musicContainer.addView(group, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ).apply { if (index > 0) topMargin = 20.toPixels() })
    }
    sceneView.ticker.songText = displayedPlayback.map { trackSummary(it.current) }
      .distinct().joinToString("     ·     ").uppercase(Locale.ROOT)
  }

  private fun trackSummary(track: SonosTrack): String =
    listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" — ")

  private suspend fun refreshWeather() {
    try {
      showWeather(weatherRepository.refreshWeather())
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: Exception) {
      hideExpiredWeather()
    }
  }

  private suspend fun refreshTransit() {
    try {
      val refreshed = transitRepository.refreshArrivals()
      transitArrivalsByRoute.clear()
      transitArrivalsByRoute.putAll(refreshed)
      lastTransitSuccessMillis = System.currentTimeMillis()
      renderTransitArrivals()
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: Exception) {
      renderTransitArrivals()
    }
  }

  private fun showWeather(weather: WeatherSnapshot) {
    if (weatherRepository.isDisplayExpired(weather)) {
      weatherView.visibility = View.GONE
      aqiView.visibility = View.GONE
      return
    }
    weatherView.text =
      "${weather.temperatureF.roundToInt()}°  ${weatherCondition(weather.weatherCode)}" +
      if (sonosState.isSplit) "" else "  ·  H ${weather.highF.roundToInt()}°  L ${weather.lowF.roundToInt()}°"
    weatherView.visibility = View.VISIBLE
    val usAqi = weather.usAqi
    if (usAqi == null || sonosState.isSplit) {
      aqiView.visibility = View.GONE
    } else {
      aqiView.text = "AQI ${usAqi}  ${usAqiCategory(usAqi)}"
      aqiView.visibility = View.VISIBLE
    }
  }

  private fun hideExpiredWeather(nowMillis: Long = System.currentTimeMillis()) {
    val cached = weatherRepository.cachedWeather()
    if (cached == null || weatherRepository.isDisplayExpired(cached, nowMillis)) {
      weatherView.visibility = View.GONE
      aqiView.visibility = View.GONE
    }
  }

  private fun renderTransitArrivals(nowMillis: Long = System.currentTimeMillis()) {
    val arrivalsAreStale = TransitRules.isDisplayStale(lastTransitSuccessMillis, nowMillis)
    for (stop in TransitConfig.stops) {
      val arrivals =
        if (arrivalsAreStale) {
          emptyList()
        } else {
          transitArrivalsByRoute[stop.displayRouteName]
            .orEmpty()
            .filter { it.arrivalTimeMillis >= nowMillis - ARRIVAL_PAST_GRACE_MILLIS }
            .take(MAX_DISPLAYED_ARRIVALS)
        }
      transitArrivalViews.getValue(stop.displayRouteName).forEachIndexed { index, view ->
        view.visibility = if (sonosState.isSplit && index > 0) View.GONE else View.VISIBLE
        val arrival = arrivals.getOrNull(index)
        if (arrival == null) {
          view.setTextColor(DreamVisualStyle.secondaryTextColor)
          view.text = NO_ARRIVALS_TEXT
        } else {
          view.text = formatTransitArrival(arrival, nowMillis)
        }
      }
    }
    transitContainer.visibility = View.VISIBLE
  }

  private fun formatTransitArrival(arrival: TransitArrival, nowMillis: Long): CharSequence {
    val arrivalText = formatArrival(arrival.arrivalTimeMillis, nowMillis)
    val prefix = "$arrivalText   → "
    val timeColor =
      if (arrival.tracked) {
        DreamVisualStyle.secondaryTextColor
      } else {
        DreamVisualStyle.scheduledTransitTextColor
      }
    val destinationColor =
      if (arrival.tracked) {
        DreamVisualStyle.transitDestinationTextColor
      } else {
        DreamVisualStyle.scheduledTransitDestinationTextColor
      }
    return SpannableString(prefix + arrival.destination).apply {
      setSpan(
        ForegroundColorSpan(timeColor),
        0,
        prefix.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
      setSpan(
        ForegroundColorSpan(destinationColor),
        prefix.length,
        length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }
  }

  private fun formatArrival(arrivalMillis: Long, nowMillis: Long): String {
    val millisUntilArrival = arrivalMillis - nowMillis
    return if (millisUntilArrival < DUE_THRESHOLD_MILLIS) {
      "Due"
    } else {
      "${(millisUntilArrival / MILLIS_PER_MINUTE).coerceAtLeast(1L)} min"
    }
  }

  private fun applyDisplayMode() {
    val clockSize =
      if (useTrolleyDisplay) {
        DreamVisualStyle.trolleyClockTextSizeSp
      } else {
        DreamVisualStyle.clockTextSizeSp
      }
    val dateSize =
      if (useTrolleyDisplay) {
        DreamVisualStyle.trolleyDateTextSizeSp
      } else {
        DreamVisualStyle.dateTextSizeSp
      }
    val weatherSize =
      if (useTrolleyDisplay) {
        DreamVisualStyle.trolleyWeatherTextSizeSp
      } else {
        DreamVisualStyle.weatherTextSizeSp
      }
    val transitSize =
      if (useTrolleyDisplay) {
        DreamVisualStyle.trolleyTransitTextSizeSp
      } else {
        DreamVisualStyle.transitTextSizeSp
      }

    clockView.textSize = if (sonosState.isSplit) clockSize * 0.68f else clockSize
    dateView.textSize = if (sonosState.isSplit) dateSize * 0.75f else dateSize
    weatherView.textSize = if (sonosState.isSplit) weatherSize * 0.75f else weatherSize
    aqiView.textSize = weatherSize
    val arrivalSize = if (sonosState.isSplit) transitSize * 0.8f else transitSize
    transitRouteLabelViews.values.forEach { it.textSize = arrivalSize }
    transitArrivalViews.values.flatten().forEach { it.textSize = arrivalSize }
    compositionView.requestLayout()
  }

  private fun applyNightBrightness() {
    applyHostBrightness(ClockPreferences.nightBrightness(this))
  }

  private fun resetBurnInPosition() {
    burnInPositionIndex = 0
    if (::compositionView.isInitialized) {
      compositionView.translationX = 0f
      compositionView.translationY = 0f
    }
    if (::musicContainer.isInitialized) {
      musicContainer.translationX = 0f
      musicContainer.translationY = 0f
      sceneView.ticker.translationY = 0f
    }
  }

  private fun Int.toPixels(): Int = (this * resources.displayMetrics.density).roundToInt()

  private companion object {
    val BURN_IN_OFFSETS_PIXELS =
      listOf(
        0 to 0,
        3 to -2,
        -3 to 2,
        2 to 4,
        -2 to -4,
        4 to 1,
        -4 to -1,
      )

    const val MILLIS_PER_MINUTE = 60_000L
    const val BRIGHTNESS_REAPPLY_DELAY_MILLIS = 1_000L
    const val BURN_IN_SHIFT_INTERVAL_MILLIS = 4L * MILLIS_PER_MINUTE
    const val DUE_THRESHOLD_MILLIS = 60_000L
    const val ARRIVAL_PAST_GRACE_MILLIS = 60_000L
    const val MAX_DISPLAYED_ARRIVALS = 2
    const val NO_ARRIVALS_TEXT = "--"
    const val TIME_12_HOUR_PATTERN = "h:mm a"
    const val TIME_24_HOUR_PATTERN = "H:mm"
    const val DATE_FORMAT_PATTERN = "EEEE, MMMM d"
  }
}
