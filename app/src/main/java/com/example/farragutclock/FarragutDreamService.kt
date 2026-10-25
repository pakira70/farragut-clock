package com.example.farragutclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.service.dreams.DreamService
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.FileDescriptor
import java.io.PrintWriter

/** System screensaver host; presentation and data remain alive across an intentional YTM handoff. */
class FarragutDreamService : DreamService() {
  private var sessionToken: ClockSessionToken? = null
  private var microphoneForeground = false

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    isInteractive = true
    isFullscreen = true
    isScreenBright = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val container = FrameLayout(this)
    setContentView(container)
    sessionToken = ClockSession.attachDream(
      this, this, container, ::applyBrightness,
      ::beginMicrophoneSession, ::endMicrophoneSession, ::finish,
    )
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    sessionToken?.let { ClockSession.configurationChanged(it, newConfig) }
  }

  override fun onDreamingStopped() {
    releaseHost()
    super.onDreamingStopped()
  }

  override fun onDetachedFromWindow() {
    releaseHost()
    super.onDetachedFromWindow()
  }

  private fun releaseHost() {
    sessionToken?.let { ClockSession.detachDream(it, this) }
    endMicrophoneSession()
  }

  override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
    super.dump(fd, writer, args)
    writer.println("Clock: ${ClockSession.debugSummary()}")
    writer.println("Dream token=${sessionToken?.id} microphoneForeground=$microphoneForeground")
  }

  private fun applyBrightness(brightness: Float) {
    val parameters = window.attributes.apply { screenBrightness = brightness }
    window.attributes = parameters
    val decorView = window.decorView
    if (decorView.isAttachedToWindow) {
      val attachedParameters = (decorView.layoutParams as? WindowManager.LayoutParams) ?: parameters
      attachedParameters.screenBrightness = brightness
      window.windowManager.updateViewLayout(decorView, attachedParameters)
    }
  }

  private fun beginMicrophoneSession(): Boolean {
    if (microphoneForeground) return true
    return try {
      val manager = getSystemService(NotificationManager::class.java)
      val channelId = "line_in_recognition"
      if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(NotificationChannel(
          channelId, "Line-In song recognition", NotificationManager.IMPORTANCE_LOW,
        ))
      }
      val settings = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
      val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
      val notification = builder
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Line-In song recognition")
        .setContentText("Identifying music while the clock is visible")
        .setContentIntent(settings)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .build()
      if (Build.VERSION.SDK_INT >= 30) {
        startForeground(107, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      } else startForeground(107, notification)
      microphoneForeground = true
      true
    } catch (_: RuntimeException) {
      endMicrophoneSession()
      false
    }
  }

  private fun endMicrophoneSession() {
    if (microphoneForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    microphoneForeground = false
  }
}
