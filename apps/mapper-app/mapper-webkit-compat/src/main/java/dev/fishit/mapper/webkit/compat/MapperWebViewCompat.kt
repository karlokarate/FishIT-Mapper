package dev.fishit.mapper.webkit.compat

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient

interface MapperRendererHealthListener {
    fun onRendererUnresponsive(url: String?)
    fun onRendererResponsive(url: String?)
}

data class MapperAppliedWebViewFeatures(
    val forceDarkStrategyApplied: Boolean,
    val algorithmicDarkeningApplied: Boolean,
    val rendererHealthHookInstalled: Boolean,
)

data class MapperSiteDataClearResult(
    val origin: String?,
    val cookiesCleared: Boolean,
    val storageCleared: Boolean,
)

class MapperWebViewCompat(
    private val features: MapperWebViewFeatures = MapperWebViewFeatures(),
) {
    fun applySafeDarkModeSettings(
        settings: WebSettings,
        allowAlgorithmicDarkening: Boolean,
    ): MapperAppliedWebViewFeatures {
        val featureState = features.state()
        var forceDarkApplied = false
        var algorithmicApplied = false

        if (featureState.forceDarkStrategy) {
            WebSettingsCompat.setForceDarkStrategy(
                settings,
                WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
            )
            forceDarkApplied = true
        }

        if (allowAlgorithmicDarkening && featureState.algorithmicDarkening) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            algorithmicApplied = true
        }

        return MapperAppliedWebViewFeatures(
            forceDarkStrategyApplied = forceDarkApplied,
            algorithmicDarkeningApplied = algorithmicApplied,
            rendererHealthHookInstalled = false,
        )
    }

    fun installRendererHealthHooks(
        webView: WebView,
        listener: MapperRendererHealthListener? = null,
    ): Boolean {
        val featureState = features.state()
        if (!featureState.rendererClient) {
            return false
        }

        WebViewCompat.setWebViewRenderProcessClient(
            webView,
            ContextCompat.getMainExecutor(webView.context),
            object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(
                    view: WebView,
                    renderer: WebViewRenderProcess?,
                ) {
                    listener?.onRendererUnresponsive(view.url)
                }

                override fun onRenderProcessResponsive(
                    view: WebView,
                    renderer: WebViewRenderProcess?,
                ) {
                    listener?.onRendererResponsive(view.url)
                }
            },
        )
        return true
    }

    fun clearSiteData(
        url: String?,
        includeCookies: Boolean = true,
        includeStorage: Boolean = true,
    ): MapperSiteDataClearResult {
        val origin = buildOrigin(url)
        var cookiesCleared = false
        var storageCleared = false

        if (includeCookies) {
            val cookieManager = CookieManager.getInstance()
            if (!url.isNullOrBlank()) {
                cookieManager.setCookie(url, "")
            }
            cookieManager.removeSessionCookies(null)
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            cookiesCleared = true
        }

        if (includeStorage) {
            val webStorage = WebStorage.getInstance()
            if (!origin.isNullOrBlank()) {
                webStorage.deleteOrigin(origin)
            } else {
                webStorage.deleteAllData()
            }
            storageCleared = true
        }

        return MapperSiteDataClearResult(
            origin = origin,
            cookiesCleared = cookiesCleared,
            storageCleared = storageCleared,
        )
    }

    fun clearAllSiteData(onCookiesCleared: ValueCallback<Boolean>? = null) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(onCookiesCleared)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun buildOrigin(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }
}
