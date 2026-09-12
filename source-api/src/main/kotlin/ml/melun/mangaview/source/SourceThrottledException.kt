package ml.melun.mangaview.source

import java.io.IOException

/** The provider is rate-limiting this client and the request cannot be retried further. */
class SourceThrottledException(message: String) : IOException(message)
