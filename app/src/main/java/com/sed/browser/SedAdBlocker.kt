package com.sed.browser

import android.net.Uri

/**
 * Lightweight request filter for WebView. It blocks known third-party ad hosts
 * and a small set of YouTube ad-specific paths without blocking googlevideo media.
 */
object SedAdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adform.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com"
    )

    private val blockedYouTubePaths = listOf(
        "/pagead/",
        "/api/stats/ads"
    )

    fun shouldBlock(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val hostBlocked = blockedHosts.any { host == it || host.endsWith(".$it") }
        if (hostBlocked) return true

        // YouTube's ad endpoints only; do not block googlevideo.com, which carries video.
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val path = uri.path?.lowercase() ?: return false
            return blockedYouTubePaths.any { path.contains(it) }
        }
        return false
    }
}
