package io.hacalsync.ha

import okhttp3.HttpUrl
import io.hacalsync.Const
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates the user-entered Home Assistant URL before any token is sent to it.
 *
 * - https:// is always allowed.
 * - http:// is only allowed for local-network / VPN addresses, so the token is
 *   never sent unencrypted over the internet.
 * - Credentials, query strings and fragments in the URL are rejected.
 */
object UrlPolicy {

    fun check(raw: String): HttpUrl {
        val url = raw.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL. Example: https://myhome.example.com")
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Don't put a username or password in the URL"
        }
        require(url.query == null && url.fragment == null) {
            "The URL must not contain ? or # parts"
        }
        require(url.isHttps || isLocalHost(url.host)) {
            "http:// is only allowed for local network addresses (e.g. 192.168.x.x, *.local). " +
                "Use https:// for remote access."
        }
        return url
    }

    private val PATH_CHARS = Regex("^/[A-Za-z0-9._~\\-/]*$")
    private val QUERY_CHARS = Regex("^[A-Za-z0-9._~\\-=&%+]*$")

    /**
     * Normalises the "page to open" setting to a path on the user's own HA, e.g.
     * "/dashboard-family/calendar". Accepts a bare path, a path without the leading slash,
     * or a full pasted link (only its path and query are kept, never its host).
     * Rejects anything that could point outside HA (schemes, "//host", "..").
     */
    fun checkPath(raw: String?): String {
        var input = raw?.trim().orEmpty()
        if (input.isEmpty()) return Const.DEFAULT_OPEN_PATH
        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            val url = input.toHttpUrlOrNull() ?: throw IllegalArgumentException("That link isn't valid")
            input = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        }
        if (!input.startsWith("/")) input = "/$input"
        input = input.trimEnd('/').ifEmpty { "/" }

        val path = input.substringBefore('?')
        val query = input.substringAfter('?', "")
        val segments = path.split('/').drop(1)
        require(
            PATH_CHARS.matches(path) && !path.startsWith("//") &&
                segments.none { it == ".." || it == "." } && QUERY_CHARS.matches(query)
        ) { "The page should look like /dashboard-name/view-name" }
        return input
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".internal") || h.endsWith(".home.arpa")
        ) return true

        val parts = h.split('.')
        if (parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 ||                       // 10.0.0.0/8
                a == 127 ||                         // loopback
                (a == 192 && b == 168) ||           // 192.168.0.0/16
                (a == 172 && b in 16..31) ||        // 172.16.0.0/12
                (a == 169 && b == 254) ||           // link-local
                (a == 100 && b in 64..127)          // CGNAT range used by Tailscale (WireGuard-encrypted)
        }

        if (h.contains(':')) {                      // IPv6 literal (HttpUrl strips the brackets)
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        return false
    }
}
