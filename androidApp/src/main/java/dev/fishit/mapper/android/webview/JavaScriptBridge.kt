package dev.fishit.mapper.android.webview

import android.webkit.JavascriptInterface
import dev.fishit.mapper.contract.UserActionEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.datetime.Clock

/**
 * JavaScript bridge that allows web pages to send user action events back to the Android app.
 *
 * This bridge is exposed to JavaScript via `addJavascriptInterface()` and allows tracking of user
 * interactions like clicks, scrolls, form submissions, etc.
 *
 * Includes input validation and dynamic recording state checking to ensure only valid data is
 * processed and only when recording is active.
 */
class JavaScriptBridge(
        private val isRecording: () -> Boolean,
        private val onUserAction: (UserActionEvent) -> Unit
) {

    /**
     * Records a click event from JavaScript. Called when user clicks on an element in the web page.
     *
     * @param targetSelector CSS selector or element identifier (e.g., "#button1", ".nav-link")
     * @param targetText Text content of the clicked element
     * @param x X coordinate of the click
     * @param y Y coordinate of the click
     */
    @JavascriptInterface
    fun recordClick(targetSelector: String, targetText: String, x: Int, y: Int) {
        if (!isRecording()) return

        val sanitizedSelector = sanitizeInput(targetSelector, MAX_SELECTOR_LENGTH)
        val sanitizedText = sanitizeInput(targetText, MAX_TEXT_LENGTH)

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "click",
                        target =
                                buildTargetString(
                                        sanitizedSelector,
                                        sanitizedText,
                                        mapOf("x" to x.toString(), "y" to y.toString())
                                )
                )
        onUserAction(event)
    }

    /**
     * Records an extended click event with link-aware data. This is the preferred method for
     * machine-readable click correlation.
     *
     * @param selector CSS selector of the clicked element
     * @param text Text content of the clicked element
     * @param x X coordinate of the click
     * @param y Y coordinate of the click
     * @param tagName HTML tag name of the clicked element
     * @param href Resolved href if clicking on or within an anchor element
     * @param pageUrl Current page URL
     */
    @JavascriptInterface
    fun recordClickExtended(
            selector: String,
            text: String,
            x: Int,
            y: Int,
            tagName: String,
            href: String,
            pageUrl: String
    ) {
        if (!isRecording()) return

        val sanitizedSelector = sanitizeInput(selector, MAX_SELECTOR_LENGTH)
        val sanitizedText = sanitizeInput(text, MAX_TEXT_LENGTH)
        val sanitizedHref = sanitizeInput(href, MAX_URL_LENGTH)
        val sanitizedPageUrl = sanitizeInput(pageUrl, MAX_URL_LENGTH)
        val sanitizedTagName = sanitizeInput(tagName, MAX_TAG_LENGTH)

        // Build structured payload for machine-readable correlation
        val payload =
                mutableMapOf(
                        "selector" to sanitizedSelector,
                        "text" to sanitizedText,
                        "x" to x.toString(),
                        "y" to y.toString(),
                        "tagName" to sanitizedTagName,
                        "pageUrl" to sanitizedPageUrl
                )
        if (sanitizedHref.isNotEmpty()) {
            payload["href"] = sanitizedHref
        }

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "click",
                        target =
                                buildTargetString(
                                        sanitizedSelector,
                                        sanitizedText,
                                        mapOf("x" to x.toString(), "y" to y.toString())
                                ),
                        payload = payload
                )
        onUserAction(event)
    }

    /**
     * Records a scroll event from JavaScript. Called when user scrolls the page.
     *
     * @param scrollY Vertical scroll position in pixels
     * @param scrollX Horizontal scroll position in pixels
     */
    @JavascriptInterface
    fun recordScroll(scrollY: Int, scrollX: Int) {
        if (!isRecording()) return

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "scroll",
                        target = "scrollY=$scrollY, scrollX=$scrollX"
                )
        onUserAction(event)
    }

    /**
     * Records a form submission from JavaScript. Called when a form is submitted.
     *
     * @param formAction The form's action URL
     * @param formMethod The form's method (GET/POST)
     * @param fieldNames JSON array of form field names (NOT values for privacy)
     */
    @JavascriptInterface
    fun recordFormSubmit(formAction: String, formMethod: String, fieldNames: String) {
        if (!isRecording()) return

        val sanitizedAction = sanitizeInput(formAction, MAX_URL_LENGTH)
        val sanitizedMethod = sanitizeInput(formMethod, MAX_METHOD_LENGTH)
        val sanitizedFieldNames = sanitizeInput(fieldNames, MAX_FIELD_NAMES_LENGTH)

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "formSubmit",
                        target =
                                "action=$sanitizedAction, method=$sanitizedMethod, fields=$sanitizedFieldNames"
                )
        onUserAction(event)
    }

    /**
     * Records an extended form submission with page context. This is the preferred method for
     * machine-readable form correlation.
     *
     * @param formAction The form's action URL
     * @param formMethod The form's method (GET/POST)
     * @param fieldNames JSON array of form field names (NOT values for privacy)
     * @param pageUrl Current page URL
     */
    @JavascriptInterface
    fun recordFormSubmitExtended(
            formAction: String,
            formMethod: String,
            fieldNames: String,
            pageUrl: String
    ) {
        if (!isRecording()) return

        val sanitizedAction = sanitizeInput(formAction, MAX_URL_LENGTH)
        val sanitizedMethod = sanitizeInput(formMethod, MAX_METHOD_LENGTH)
        val sanitizedFieldNames = sanitizeInput(fieldNames, MAX_FIELD_NAMES_LENGTH)
        val sanitizedPageUrl = sanitizeInput(pageUrl, MAX_URL_LENGTH)

        // Build structured payload for machine-readable correlation
        val payload =
                mapOf(
                        "action" to sanitizedAction,
                        "method" to sanitizedMethod,
                        "fields" to sanitizedFieldNames,
                        "pageUrl" to sanitizedPageUrl
                )

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "formSubmit",
                        target =
                                "action=$sanitizedAction, method=$sanitizedMethod, fields=$sanitizedFieldNames",
                        payload = payload
                )
        onUserAction(event)
    }

    /**
     * Records an input/change event on a form field.
     *
     * @param fieldName Name or ID of the field
     * @param fieldType Type of the field (text, email, etc.)
     */
    @JavascriptInterface
    fun recordFieldChange(fieldName: String, fieldType: String) {
        if (!isRecording()) return

        val sanitizedFieldName = sanitizeInput(fieldName, MAX_FIELD_NAME_LENGTH)
        val sanitizedFieldType = sanitizeInput(fieldType, MAX_FIELD_TYPE_LENGTH)

        val event =
                UserActionEvent(
                        id = IdGenerator.newEventId(),
                        at = Clock.System.now(),
                        action = "fieldChange",
                        target = "field=$sanitizedFieldName, type=$sanitizedFieldType"
                )
        onUserAction(event)
    }

    /** Builds a compact target string from selector, text, and metadata. */
    private fun buildTargetString(
            selector: String,
            text: String,
            metadata: Map<String, String>
    ): String {
        val truncatedText = if (text.length > 100) text.take(97) + "..." else text
        val metaStr = metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return "$selector | $truncatedText | $metaStr"
    }

    /** Sanitizes input by limiting length and removing potentially malicious characters. */
    private fun sanitizeInput(input: String, maxLength: Int): String {
        // Limit length
        val truncated =
                if (input.length > maxLength) {
                    input.take(maxLength)
                } else {
                    input
                }

        // Remove control characters and null bytes
        return truncated.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
    }

    companion object {
        private const val MAX_SELECTOR_LENGTH = 500
        private const val MAX_TEXT_LENGTH = 1000
        private const val MAX_URL_LENGTH = 2000
        private const val MAX_METHOD_LENGTH = 10
        private const val MAX_FIELD_NAMES_LENGTH = 5000
        private const val MAX_FIELD_NAME_LENGTH = 200
        private const val MAX_FIELD_TYPE_LENGTH = 50
        private const val MAX_TAG_LENGTH = 50
    }
}
