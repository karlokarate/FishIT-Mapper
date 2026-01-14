package dev.fishit.mapper.android.webview

import android.webkit.JavascriptInterface
import dev.fishit.mapper.contract.UserActionEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.datetime.Clock

/**
 * JavaScript bridge that allows web pages to send user action events back to the Android app.
 * 
 * This bridge is exposed to JavaScript via `addJavascriptInterface()` and allows tracking
 * of user interactions like clicks, scrolls, form submissions, etc.
 */
class JavaScriptBridge(
    private val onUserAction: (UserActionEvent) -> Unit
) {
    
    /**
     * Records a click event from JavaScript.
     * Called when user clicks on an element in the web page.
     * 
     * @param targetSelector CSS selector or element identifier (e.g., "#button1", ".nav-link")
     * @param targetText Text content of the clicked element
     * @param x X coordinate of the click
     * @param y Y coordinate of the click
     */
    @JavascriptInterface
    fun recordClick(targetSelector: String, targetText: String, x: Int, y: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "click:$targetSelector:$targetText:x=$x,y=$y",
            target = targetSelector.takeIf { it.isNotBlank() }
        )
        onUserAction(event)
    }
    
    /**
     * Records a scroll event from JavaScript.
     * Called when user scrolls the page.
     * 
     * @param scrollX Horizontal scroll position
     * @param scrollY Vertical scroll position
     */
    @JavascriptInterface
    fun recordScroll(scrollX: Int, scrollY: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "scroll:x=$scrollX,y=$scrollY",
            target = null
        )
        onUserAction(event)
    }
    
    /**
     * Records a form submission from JavaScript.
     * Called when a form is submitted.
     * 
     * @param formAction The form's action URL
     * @param formMethod The form's method (GET/POST)
     * @param formId The form's ID attribute
     */
    @JavascriptInterface
    fun recordFormSubmit(formAction: String, formMethod: String, formId: String) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "form_submit:$formMethod:$formAction",
            target = formId.takeIf { it.isNotBlank() }
        )
        onUserAction(event)
    }
    
    /**
     * Records a generic input event from JavaScript.
     * Called when user interacts with form inputs.
     * 
     * @param inputType Type of input (text, checkbox, etc.)
     * @param inputName Name attribute of the input
     * @param action Action performed (focus, blur, change)
     */
    @JavascriptInterface
    fun recordInput(inputType: String, inputName: String, action: String) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            action = "input:$action:$inputType",
            target = inputName.takeIf { it.isNotBlank() }
        )
        onUserAction(event)
    }
}
