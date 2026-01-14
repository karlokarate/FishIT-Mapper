package dev.fishit.mapper.engine

/**
 * Tiny URL normalization helpers.
 *
 * MVP rules:
 * - trim whitespace
 * - remove fragment (#...)
 * - keep query string (because it can matter for apps), but you can change this later.
 */
object UrlNormalizer {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val hash = trimmed.indexOf('#')
        return if (hash >= 0) trimmed.substring(0, hash) else trimmed
    }
}
