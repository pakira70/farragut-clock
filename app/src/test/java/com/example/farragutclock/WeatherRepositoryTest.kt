package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherRepositoryTest {
  @Test
  fun usAqiCategoryUsesEpaBreakpoints() {
    assertEquals("Good", usAqiCategory(12))
    assertEquals("Moderate", usAqiCategory(96))
    assertEquals("Sensitive", usAqiCategory(125))
    assertEquals("Unhealthy", usAqiCategory(180))
    assertEquals("Very unhealthy", usAqiCategory(250))
    assertEquals("Hazardous", usAqiCategory(320))
  }
}
