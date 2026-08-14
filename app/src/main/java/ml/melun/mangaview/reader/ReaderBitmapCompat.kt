package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.os.Build

/** Avoids resolving the API-26 HARDWARE enum on older supported devices. */
internal fun Bitmap.isHardwareConfigCompat(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE
