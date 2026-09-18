package ml.melun.mangaview.activity

import ml.melun.mangaview.engine.content.PageHttpException

/**
 * Korean copy for the viewer failure card. Provider exceptions carry raw English text
 * ("Page request returned HTTP 403"); it must never reach the user-facing card.
 */
internal fun viewerFailureMessage(failure: Throwable): String = when (failure) {
    is PageHttpException -> "페이지를 불러오지 못했습니다 (HTTP ${failure.statusCode})"
    else -> failure.message?.takeIf { message -> message.isNotBlank() && message.any { it in '가'..'힣' } }
        ?: "페이지를 불러오지 못했습니다"
}
