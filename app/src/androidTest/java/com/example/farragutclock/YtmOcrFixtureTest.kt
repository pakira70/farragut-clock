package com.example.farragutclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The already-observed YTM result, processed locally without microphone or provider calls. */
@RunWith(AndroidJUnit4::class)
class YtmOcrFixtureTest {
  @Test
  fun readsSongAndArtistFromActualTabletResult() {
    assertFixture(
      assetName = "ytm-result-jungle.png",
      title = "(More and More) It Ain't Easy",
      artist = "Jungle",
    )
  }

  @Test
  fun readsCultureClubMedleyFromActualTabletResult() {
    assertFixture(
      assetName = "ytm-result-culture-club-metadata.png",
      title = "It's A Miracle / Miss Me Blind",
      artist = "Culture Club",
      metadataAlreadyCropped = true,
    )
  }

  private fun assertFixture(
    assetName: String,
    title: String,
    artist: String,
    metadataAlreadyCropped: Boolean = false,
  ) {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val full = requireNotNull(assets.open(assetName).use(BitmapFactory::decodeStream))
    // Culture Club stores only this public metadata crop, excluding artwork and other UI.
    val metadata = if (metadataAlreadyCropped) full else Bitmap.createBitmap(full, 175, 753, 450, 83)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      val text = Tasks.await(recognizer.process(InputImage.fromBitmap(metadata, 0)), 30, TimeUnit.SECONDS)
      val lines = text.textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
          line.boundingBox?.let { box ->
            YtmOcrLine(line.text, box.left, box.top, box.right, box.bottom)
          }
        }
      }
      val song = parseYtmResult(title, lines, metadata.width, metadata.height)
      val diagnostics = buildString {
        append("fixture=$assetName; crop=${metadata.width}x${metadata.height}; ")
        append("hint='${title.take(100)}'; hintKey=${ytmTextKey(title).take(100)}; rows=${lines.size}; ")
        lines.take(6).forEach { line ->
          append("text='${line.text.take(100)}'; key=${ytmTextKey(line.text).take(100)}; ")
          append("cp=${line.text.take(48).map { it.code.toString(16) }} ")
          append("@ ${line.left},${line.top},${line.right},${line.bottom}; ")
        }
      }
      assertNotNull("OCR metadata rejected: $diagnostics", song)
      assertEquals("Wrong title: $diagnostics", title, song!!.title)
      assertEquals("Wrong artist: $diagnostics", artist, song.artist)
    } finally {
      recognizer.close()
      metadata.recycle()
      if (full !== metadata) full.recycle()
    }
  }
}
