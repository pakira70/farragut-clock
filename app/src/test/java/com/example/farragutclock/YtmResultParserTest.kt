package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtmResultParserTest {
  private val title = "(More and More) It Ain't Easy"

  @Test
  fun extractsArtistBetweenExactTitleAndAlbumYear() {
    val result = parseYtmResult(title, fixture(), 450, 100)
    assertEquals(RecognizedSong(title, "Jungle"), result)
  }

  @Test
  fun typographicQuotesAndOcrPunctuationDoNotChangeTheAccessibleTitle() {
    val result = parseYtmResult(title, fixture("(More and More) It Ain’t Easy"), 450, 100)
    assertEquals(title, result?.title)
    assertEquals("Jungle", result?.artist)
  }

  @Test
  fun acceptsWrappedTitlesButRequiresAllWords() {
    val wrapped = listOf(
      line("(More and More)", 3, 19),
      line("It Ain't Easy", 23, 39),
      line("Jungle", 43, 59),
      line("For Ever · 2018", 63, 79),
    )
    assertEquals("Jungle", parseYtmResult(title, wrapped, 450, 100)?.artist)
    assertNull(parseYtmResult(title, wrapped.drop(1), 450, 100))
  }

  @Test
  fun missingArtistCannotTurnTheAlbumIntoAnArtist() {
    assertNull(parseYtmResult(title, listOf(line(title, 3, 19), line("For Ever · 2018", 25, 41)), 450, 100))
  }

  @Test
  fun missingAlbumFooterOrChangedLayoutFailsClosed() {
    assertNull(parseYtmResult(title, fixture().dropLast(1), 450, 100))
    assertNull(parseYtmResult(title, fixture().mapIndexed { index, value ->
      if (index == 1) value.copy(left = 180) else value
    }, 450, 100))
  }

  @Test
  fun wrongOrRepeatedTitleCannotBecomeARecognition() {
    assertNull(parseYtmResult("Another song", fixture(), 450, 100))
    assertNull(parseYtmResult(title, fixture() + fixture().map { it.copy(top = it.top + 100, bottom = it.bottom + 100) }, 450, 220))
  }

  @Test
  fun controlsAndOutOfBoundsTextCannotBecomeArtists() {
    assertNull(parseYtmResult(title, fixture().mapIndexed { index, value ->
      if (index == 1) value.copy(text = "Save to playlist") else value
    }, 450, 100))
    assertNull(parseYtmResult(title, fixture().mapIndexed { index, value ->
      if (index == 1) value.copy(right = 453) else value
    }, 450, 100))
  }

  @Test
  fun actualTabletOcrAllowsOnePixelGlyphOverhangAtCropEdges() {
    val actualRows = listOf(
      YtmOcrLine(title, 0, 14, 227, 34),
      YtmOcrLine("Jungle", -1, 39, 51, 53),
      YtmOcrLine("For Ever · 2018", 4, 61, 115, 73),
    )
    assertEquals(RecognizedSong(title, "Jungle"), parseYtmResult(title, actualRows, 450, 83))
    assertEquals(RecognizedSong(title, "Jungle"), parseYtmResult(title, actualRows.mapIndexed { index, row ->
      if (index == 1) row.copy(right = 451) else row
    }, 450, 83))
    assertNull(parseYtmResult(title, actualRows.mapIndexed { index, row ->
      if (index == 1) row.copy(left = -3) else row
    }, 450, 83))
  }

  @Test
  fun actualCultureClubOcrAcceptsHyphenBeforeAlbumYear() {
    val title = "It's A Miracle / Miss Me Blind"
    val actualRows = listOf(
      YtmOcrLine(title, 5, 15, 219, 31),
      YtmOcrLine("Culture Club", 4, 39, 97, 51),
      YtmOcrLine("The Remix Collection - 1991", 4, 61, 208, 73),
    )
    assertEquals(RecognizedSong(title, "Culture Club"), parseYtmResult(title, actualRows, 450, 83))
  }

  @Test
  fun actualAutomaticOcrAcceptsAlbumYearWithAnOmittedMiddot() {
    val rows = listOf(
      YtmOcrLine("Zombies", 22, 13, 87, 25),
      YtmOcrLine("Childish Gambino", 22, 35, 158, 47),
      YtmOcrLine("\"Awaken, My Love!\" 2016", 22, 53, 224, 72),
    )
    assertEquals(RecognizedSong("Zombies", "Childish Gambino"), parseYtmResult("Zombies", rows, 491, 88))
    assertNull(parseYtmResult("Another song", rows, 491, 88))
    assertNull(parseYtmResult("Zombies", rows.dropLast(1), 491, 88))
    assertNull(parseYtmResult("Zombies", rows.mapIndexed { index, row ->
      if (index == 2) row.copy(text = "\"Awaken, My Love!\" 201") else row
    }, 491, 88))
  }

  @Test
  fun hyphenAlbumSeparatorStillRequiresExactTitleAndCompleteYear() {
    val title = "It's A Miracle / Miss Me Blind"
    val actualRows = listOf(
      YtmOcrLine(title, 5, 15, 219, 31),
      YtmOcrLine("Culture Club", 4, 39, 97, 51),
      YtmOcrLine("The Remix Collection - 1991", 4, 61, 208, 73),
    )
    assertNull(parseYtmResult("It's A Miracle", actualRows, 450, 83))
    listOf("The Remix Collection -", "The Remix Collection - 199", "The Remix Collection - 199l")
      .forEach { invalidFooter ->
        assertNull(parseYtmResult(title, actualRows.mapIndexed { index, row ->
          if (index == 2) row.copy(text = invalidFooter) else row
        }, 450, 83))
      }
  }

  private fun fixture(ocrTitle: String = title) = listOf(
    line(ocrTitle, 3, 19), line("Jungle", 25, 41), line("For Ever · 2018", 47, 63),
  )

  private fun line(text: String, top: Int, bottom: Int) = YtmOcrLine(text, 4, top, 440, bottom)
}
