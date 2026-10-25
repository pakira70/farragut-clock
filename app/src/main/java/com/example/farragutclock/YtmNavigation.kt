package com.example.farragutclock

/** Geometry-only selection keeps a content item named Search from becoming a navigation action. */
internal data class YtmNavigationBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  fun contains(other: YtmNavigationBounds): Boolean = other.width > 0 && other.height > 0 &&
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

internal data class YtmNavigationControl(
  val text: String,
  val description: String,
  val clickable: Boolean,
  val bounds: YtmNavigationBounds,
  val clickableAncestor: YtmNavigationBounds? = null,
)

internal enum class YtmNavigationAction { NONE, TOP_SEARCH, BOTTOM_SEARCH, SOUND_SEARCH }
internal const val YTM_LONG_SOUND_SEARCH_DESCRIPTION =
  "Tap to search for a song that is playing or by humming or singing"
internal const val YTM_TOOLBAR_SOUND_SEARCH_DESCRIPTION = "Sound search"
internal data class YtmSearchTarget(
  val index: Int,
  val action: YtmNavigationAction,
  val clickBounds: YtmNavigationBounds,
)

internal fun selectYtmSoundSearchTarget(
  controls: List<YtmNavigationControl>,
  root: YtmNavigationBounds,
): YtmSearchTarget? {
  if (root.width <= 0 || root.height <= 0) return null
  val candidates = controls.mapIndexedNotNull { index, control ->
    if (!control.clickable) return@mapIndexedNotNull null
    val eligible = when (control.description) {
      YTM_LONG_SOUND_SEARCH_DESCRIPTION -> true // Existing proven result-page control.
      YTM_TOOLBAR_SOUND_SEARCH_DESCRIPTION -> root.contains(control.bounds) &&
        control.bounds.bottom <= root.top + root.height / 6 &&
        control.bounds.width <= root.width / 3 && control.bounds.height <= root.height / 8
      else -> false
    }
    if (eligible) YtmSearchTarget(index, YtmNavigationAction.SOUND_SEARCH, control.bounds) else null
  }
  return candidates.distinctBy { it.clickBounds }.singleOrNull()
}

internal fun selectYtmSearchTarget(
  controls: List<YtmNavigationControl>,
  root: YtmNavigationBounds,
): YtmSearchTarget? {
  if (root.width <= 0 || root.height <= 0) return null
  // Retain the known toolbar Search button, confined to a small top-bar control.
  controls.forEachIndexed { index, control ->
    if (control.description == "Search" && control.clickable && root.contains(control.bounds) &&
      control.bounds.bottom <= root.top + root.height / 6 &&
      control.bounds.width <= root.width / 3 && control.bounds.height <= root.height / 8) {
      return YtmSearchTarget(index, YtmNavigationAction.TOP_SEARCH, control.bounds)
    }
  }
  fun isBottomLabel(control: YtmNavigationControl, label: String): Boolean =
    (control.text == label || control.description == label) && root.contains(control.bounds) &&
      control.bounds.top >= root.top + root.height * 3 / 4 &&
      control.bounds.width <= root.width / 2 && control.bounds.height <= root.height / 5
  val homes = controls.filter { isBottomLabel(it, "Home") }
  val libraries = controls.filter { isBottomLabel(it, "Library") }
  if (homes.isEmpty() || libraries.isEmpty()) return null
  val candidates = controls.mapIndexedNotNull { index, control ->
    if (!isBottomLabel(control, "Search")) return@mapIndexedNotNull null
    val clickBounds = if (control.clickable) control.bounds else control.clickableAncestor
      ?: return@mapIndexedNotNull null
    if (!root.contains(clickBounds) || !clickBounds.contains(control.bounds) ||
      clickBounds.top < root.top + root.height * 3 / 4 ||
      clickBounds.width > root.width / 2 || clickBounds.height > root.height / 5) return@mapIndexedNotNull null
    val center = (control.bounds.top + control.bounds.bottom) / 2
    fun isSameRow(other: YtmNavigationControl): Boolean =
      kotlin.math.abs((other.bounds.top + other.bounds.bottom) / 2 - center) <= root.height / 20
    if (homes.none(::isSameRow) || libraries.none(::isSameRow)) return@mapIndexedNotNull null
    YtmSearchTarget(index, YtmNavigationAction.BOTTOM_SEARCH, clickBounds)
  }
  // The same tab may expose its label and description on adjacent nodes; they must resolve to one target.
  return candidates.distinctBy { it.clickBounds }.singleOrNull()
}
