package dev.fishit.mapper.android.webview

import android.webkit.JavascriptInterface
import dev.fishit.mapper.contract.UserActionEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.datetime.Clock

/**
 * JavaScript interface for capturing user interactions from web pages.
 * 
 * This bridge allows web pages to report user actions (clicks, scrolls, form submits)
 * back to the native Android app for tracking and analysis.
 */
class JavaScriptBridge(
    private val onUserAction: (UserActionEvent) -> Unit
) {
    
    /**
     * Records a click event from the web page.
     * 
     * @param targetSelector CSS selector of the clicked element
     * @param targetText Text content of the clicked element (truncated to 100 chars)
     * @param x X coordinate of the click
     * @param y Y coordinate of the click
     */
    @JavascriptInterface
    fun recordClick(targetSelector: String, targetText: String, x: Int, y: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "click",
            target = buildTargetString(targetSelector, targetText, mapOf("x" to x.toString(), "y" to y.toString()))
        )
        onUserAction(event)
    }
    
    /**
     * Records a scroll event from the web page.
     * 
     * @param scrollY Vertical scroll position in pixels
     * @param scrollX Horizontal scroll position in pixels
     */
    @JavascriptInterface
    fun recordScroll(scrollY: Int, scrollX: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "scroll",
            target = "scrollY=$scrollY, scrollX=$scrollX"
        )
        onUserAction(event)
    }
    
    /**
     * Records a form submit event from the web page.
     * 
     * @param formAction The form's action URL
     * @param formMethod The form's method (GET/POST)
     * @param fieldNames Comma-separated list of form field names (NOT values for privacy)
     */
    @JavascriptInterface
    fun recordFormSubmit(formAction: String, formMethod: String, fieldNames: String) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "formSubmit",
            target = "action=$formAction, method=$formMethod, fields=[$fieldNames]"
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
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "fieldChange",
            target = "field=$fieldName, type=$fieldType"
        )
        onUserAction(event)
    }
    
    /**
     * Builds a compact target string from selector, text, and metadata.
     */
    private fun buildTargetString(selector: String, text: String, metadata: Map<String, String>): String {
        val truncatedText = if (text.length > 100) text.take(97) + "..." else text
        val metaStr = metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return "$selector | $truncatedText | $metaStr"
    }
}
