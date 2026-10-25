package com.example.farragutclock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** Muted amber dot-matrix lettering inspired by the Yamaha CDX-E100 readout. */
internal object SonosVisualStyle {
  val primaryTextColor: Int = Color.rgb(201, 154, 66)
  val secondaryTextColor: Int = Color.rgb(159, 121, 55)

  fun typeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.dot_matrix) ?: Typeface.MONOSPACE
}
