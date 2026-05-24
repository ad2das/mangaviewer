package ml.melun.mangaview.reader

import android.graphics.Bitmap

data class ReaderTile(
    val sourceTop: Int,
    val sourceBottom: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bitmap: Bitmap
)
