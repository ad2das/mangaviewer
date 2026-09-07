package ml.melun.mangaview.source

import java.io.IOException

/** The provider still declares a page whose current origin confirms that no bytes exist. */
class SourcePageUnavailableException(message: String) : IOException(message)
