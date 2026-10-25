package com.example.farragutclock

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneSampleTest {
  @Test
  fun silenceHasFiniteFloorAndNoClipping() {
    val levels = pcm16Levels(ShortArray(100))
    assertEquals(-96f, levels.rmsDbFS, 0f)
    assertEquals(-96f, levels.peakDbFS, 0f)
    assertEquals(0f, levels.clippingPercent, 0f)
    assertEquals(levels, pcm16Levels(shortArrayOf()))
  }

  @Test
  fun rmsAndPeakUseFullScaleReferenceAndHandleNegativeLimit() {
    val halfScale = pcm16Levels(shortArrayOf(16_384, -16_384))
    assertEquals(-6.0206f, halfScale.rmsDbFS, 0.001f)
    assertEquals(-6.0206f, halfScale.peakDbFS, 0.001f)
    assertEquals(0f, halfScale.clippingPercent, 0f)

    val mixed = pcm16Levels(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 0))
    assertEquals(-3.0104f, mixed.rmsDbFS, 0.001f)
    assertEquals(0f, mixed.peakDbFS, 0.001f)
    assertEquals(50f, mixed.clippingPercent, 0f)
  }

  @Test
  fun progressLevelsUseOnlyTheLatestReadSamples() {
    val samples = shortArrayOf(Short.MAX_VALUE, 0, 0, Short.MIN_VALUE)
    assertEquals(MicrophoneLevels(-96f, -96f, 0f), pcm16Levels(samples, 1, 2))
  }

  @Test
  fun wavHasValidMonoPcmHeaderAndLittleEndianSignedSamples() {
    val samples = shortArrayOf(0x1234, -2, Short.MIN_VALUE)
    val wav = pcm16Wav(samples, 44_100)
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(50, wav.size)
    assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    assertEquals(42, header.getInt(4))
    assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
    assertEquals(16, header.getInt(16))
    assertEquals(1, header.getShort(20).toInt())
    assertEquals(1, header.getShort(22).toInt())
    assertEquals(44_100, header.getInt(24))
    assertEquals(88_200, header.getInt(28))
    assertEquals(2, header.getShort(32).toInt())
    assertEquals(16, header.getShort(34).toInt())
    assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    assertEquals(6, header.getInt(40))
    assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1, 0, -128), wav.copyOfRange(44, wav.size))
  }
}
