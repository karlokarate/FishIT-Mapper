package dev.fishit.mapper.android.webview

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager für Debug-Modus und Layout-Inspektion.
 *
 * Ermöglicht das Aktivieren von Debug-Features wie:
 * - Debug-Coloring für WebView-Grenzen
 * - Overlay-Erkennung
 * - Touch-Event-Logging
 * - Layout-Hierarchie-Inspektion
 *
 * Hilfreich bei der Diagnose von:
 * - Grauen Overlays die Touch-Events blockieren
 * - Touch-Event-Interception durch App-Layer
 * - Layout-Problemen bei Consent-Dialogen
 */
object WebViewDebugManager {

    private const val PREFS_NAME = "webview_debug_prefs"
    private const val KEY_DEBUG_MODE = "debug_mode_enabled"
    private const val KEY_DEBUG_COLORING = "debug_coloring_enabled"
    private const val KEY_TOUCH_LOGGING = "touch_logging_enabled"

    private var prefs: SharedPreferences? = null

    private val _debugModeEnabled = MutableStateFlow(false)
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()

    private val _debugColoringEnabled = MutableStateFlow(false)
    val debugColoringEnabled: StateFlow<Boolean> = _debugColoringEnabled.asStateFlow()

    private val _touchLoggingEnabled = MutableStateFlow(false)
    val touchLoggingEnabled: StateFlow<Boolean> = _touchLoggingEnabled.asStateFlow()

    /**
     * Initialisiert den Debug Manager.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Load saved state
            _debugModeEnabled.value = prefs?.getBoolean(KEY_DEBUG_MODE, false) ?: false
            _debugColoringEnabled.value = prefs?.getBoolean(KEY_DEBUG_COLORING, false) ?: false
            _touchLoggingEnabled.value = prefs?.getBoolean(KEY_TOUCH_LOGGING, false) ?: false
        }
    }

    /**
     * Aktiviert/Deaktiviert den Debug-Modus.
     */
    fun setDebugModeEnabled(enabled: Boolean) {
        _debugModeEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_MODE, enabled)?.apply()
        
        // Wenn Debug-Modus deaktiviert wird, auch alle anderen Features deaktivieren
        if (!enabled) {
            setDebugColoringEnabled(false)
            setTouchLoggingEnabled(false)
        }
    }

    /**
     * Aktiviert/Deaktiviert Debug-Coloring für WebView.
     * Färbt den WebView-Hintergrund ein, um die Grenzen sichtbar zu machen.
     */
    fun setDebugColoringEnabled(enabled: Boolean) {
        _debugColoringEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_COLORING, enabled)?.apply()
    }

    /**
     * Aktiviert/Deaktiviert Touch-Event-Logging.
     * Loggt alle Touch-Events für Diagnose.
     */
    fun setTouchLoggingEnabled(enabled: Boolean) {
        _touchLoggingEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_TOUCH_LOGGING, enabled)?.apply()
    }

    /**
     * Prüft ob ein View ein Overlay über dem WebView ist.
     * 
     * Ein Overlay kann Touch-Events abfangen und verhindern,
     * dass sie den WebView erreichen.
     */
    fun detectOverlays(webView: android.webkit.WebView): List<OverlayInfo> {
        val overlays = mutableListOf<OverlayInfo>()
        
        try {
            val parent = webView.parent as? android.view.ViewGroup ?: return emptyList()
            
            // Finde alle Views die über dem WebView liegen
            val webViewIndex = parent.indexOfChild(webView)
            
            for (i in webViewIndex + 1 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                
                // Prüfe ob der sibling clickable ist oder einen OnTouchListener hat
                val isInteractive = sibling.isClickable || 
                                   sibling.hasOnClickListeners() ||
                                   sibling.hasOnTouchListeners()
                
                // Prüfe Visibility
                val isVisible = sibling.visibility == android.view.View.VISIBLE
                
                if (isVisible && isInteractive) {
                    overlays.add(
                        OverlayInfo(
                            className = sibling.javaClass.simpleName,
                            index = i,
                            isClickable = sibling.isClickable,
                            hasClickListener = sibling.hasOnClickListeners(),
                            hasTouchListener = sibling.hasOnTouchListeners(),
                            alpha = sibling.alpha,
                            bounds = sibling.getBoundsOnScreen()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebViewDebugManager", "Error detecting overlays: ${e.message}", e)
        }
        
        return overlays
    }

    /**
     * Prüft ob der WebView Touch-Events empfangen kann.
     */
    fun checkTouchEventAccess(webView: android.webkit.WebView): TouchEventAccessInfo {
        return TouchEventAccessInfo(
            isFocusable = webView.isFocusable,
            isFocusableInTouchMode = webView.isFocusableInTouchMode,
            isEnabled = webView.isEnabled,
            isClickable = webView.isClickable,
            hasOnTouchListener = webView.hasOnTouchListeners(),
            visibility = when (webView.visibility) {
                android.view.View.VISIBLE -> "VISIBLE"
                android.view.View.INVISIBLE -> "INVISIBLE"
                android.view.View.GONE -> "GONE"
                else -> "UNKNOWN"
            },
            alpha = webView.alpha,
            overlayCount = detectOverlays(webView).size
        )
    }

    data class OverlayInfo(
        val className: String,
        val index: Int,
        val isClickable: Boolean,
        val hasClickListener: Boolean,
        val hasTouchListener: Boolean,
        val alpha: Float,
        val bounds: android.graphics.Rect
    )

    data class TouchEventAccessInfo(
        val isFocusable: Boolean,
        val isFocusableInTouchMode: Boolean,
        val isEnabled: Boolean,
        val isClickable: Boolean,
        val hasOnTouchListener: Boolean,
        val visibility: String,
        val alpha: Float,
        val overlayCount: Int
    )
}

/**
 * Extension function um die Bounds eines Views auf dem Screen zu bekommen.
 */
private fun android.view.View.getBoundsOnScreen(): android.graphics.Rect {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return android.graphics.Rect(
        location[0],
        location[1],
        location[0] + width,
        location[1] + height
    )
}

/**
 * Extension function um zu prüfen ob ein View einen OnTouchListener hat.
 */
private fun android.view.View.hasOnTouchListeners(): Boolean {
    return try {
        // Reflection um private Field zu lesen
        val field = android.view.View::class.java.getDeclaredField("mOnTouchListener")
        field.isAccessible = true
        field.get(this) != null
    } catch (e: Exception) {
        false
    }
}
