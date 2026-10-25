package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtmNavigationTest {
  private val root = YtmNavigationBounds(0, 0, 800, 1340)
  private val searchTab = YtmNavigationBounds(400, 1200, 600, 1300)
  private fun label(text: String, left: Int, clickable: Boolean = false) = YtmNavigationControl(
    text, "", clickable, YtmNavigationBounds(left, 1240, left + 80, 1270),
  )
  private fun tabs() = listOf(label("Home", 60), label("Library", 660),
    label("Search", 460).copy(clickableAncestor = searchTab))

  @Test fun detailPageSearchTextUsesItsBoundedClickableTabAncestor() {
    val target = selectYtmSearchTarget(tabs(), root)
    assertEquals(YtmNavigationAction.BOTTOM_SEARCH, target?.action)
    assertEquals(searchTab, target?.clickBounds)
  }

  @Test fun clickableDescriptionOnlyTabAlsoWorks() {
    val controls = tabs().dropLast(1) + YtmNavigationControl("", "Search", true, searchTab)
    assertEquals(searchTab, selectYtmSearchTarget(controls, root)?.clickBounds)
  }

  @Test fun exactToolbarSearchRemainsAvailableWithoutBottomTabs() {
    val toolbar = YtmNavigationControl("", "Search", true, YtmNavigationBounds(700, 30, 770, 100))
    assertEquals(YtmNavigationAction.TOP_SEARCH, selectYtmSearchTarget(listOf(toolbar), root)?.action)
  }

  @Test fun searchContentCannotNavigateWithoutBothVerifiedBottomLabels() {
    assertNull(selectYtmSearchTarget(tabs().filterNot { it.text == "Home" }, root))
    assertNull(selectYtmSearchTarget(tabs().filterNot { it.text == "Library" }, root))
    assertNull(selectYtmSearchTarget(tabs().map { if (it.text == "Search")
      it.copy(bounds = YtmNavigationBounds(400, 600, 480, 630)) else it }, root))
  }

  @Test fun rootOrContentAncestorAndWrongRowAreRejected() {
    assertNull(selectYtmSearchTarget(tabs().map { if (it.text == "Search")
      it.copy(clickableAncestor = root) else it }, root))
    assertNull(selectYtmSearchTarget(tabs().map { if (it.text == "Home")
      it.copy(bounds = YtmNavigationBounds(60, 1010, 140, 1040)) else it }, root))
  }

  @Test fun ambiguousSeparateSearchTabsFailClosedButDuplicateLabelsShareOneTarget() {
    val other = label("Search", 260).copy(clickableAncestor = YtmNavigationBounds(200, 1200, 400, 1300))
    assertNull(selectYtmSearchTarget(tabs() + other, root))
    assertEquals(searchTab, selectYtmSearchTarget(tabs() + tabs().last(), root)?.clickBounds)
  }

  @Test fun observedSoundSearchToolbarButtonIsDistinctFromVoiceSearch() {
    val sound = YtmNavigationControl("", "Sound search", true, YtmNavigationBounds(701, 44, 764, 107))
    val voice = sound.copy(description = "Voice search", bounds = YtmNavigationBounds(638, 44, 701, 107))
    assertEquals(sound.bounds, selectYtmSoundSearchTarget(listOf(voice, sound), root)?.clickBounds)
    assertNull(selectYtmSoundSearchTarget(listOf(voice), root))
  }

  @Test fun soundSearchTextOrBodyContentCannotBecomeTheToolbarAction() {
    val toolbar = YtmNavigationControl("", "Sound search", true, YtmNavigationBounds(701, 44, 764, 107))
    assertNull(selectYtmSoundSearchTarget(listOf(toolbar.copy(description = "", text = "Sound search")), root))
    assertNull(selectYtmSoundSearchTarget(listOf(toolbar.copy(bounds = YtmNavigationBounds(175, 307, 625, 830))), root))
    assertNull(selectYtmSoundSearchTarget(listOf(toolbar.copy(clickable = false)), root))
  }

  @Test fun ambiguousSoundSearchToolbarTargetsFailClosed() {
    val toolbar = YtmNavigationControl("", "Sound search", true, YtmNavigationBounds(701, 44, 764, 107))
    assertNull(selectYtmSoundSearchTarget(listOf(toolbar,
      toolbar.copy(bounds = YtmNavigationBounds(638, 44, 701, 107))), root))
  }

  @Test fun provenLongDescriptionStillWorksOnTheResultPage() {
    val result = YtmNavigationControl("", YTM_LONG_SOUND_SEARCH_DESCRIPTION, true,
      YtmNavigationBounds(355, 1143, 445, 1233))
    assertEquals(result.bounds, selectYtmSoundSearchTarget(listOf(result), root)?.clickBounds)
  }
}
