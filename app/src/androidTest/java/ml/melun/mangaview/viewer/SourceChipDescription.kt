package ml.melun.mangaview.viewer

/**
 * The library source chip's accessibility description (see MainSourceChip semantics).
 *
 * The UI a11y audit made the chip announce "사이트: <label>" instead of the bare label, so
 * UiAutomator selectors must match that exact string. Keep every chip lookup on this helper.
 */
internal fun sourceChipDescription(label: String): String = "사이트: $label"
