package ml.melun.mangaview.engine.api

/** Tile geometry is rebuilt during scrolling; validation must not allocate a regex or matcher. */
internal fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
