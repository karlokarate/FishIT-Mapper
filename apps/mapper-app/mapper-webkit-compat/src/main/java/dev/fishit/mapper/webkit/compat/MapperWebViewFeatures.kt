package dev.fishit.mapper.webkit.compat

import androidx.webkit.WebViewFeature

interface MapperWebViewFeatureChecker {
    fun isSupported(feature: String): Boolean
}

object AndroidxMapperWebViewFeatureChecker : MapperWebViewFeatureChecker {
    override fun isSupported(feature: String): Boolean = WebViewFeature.isFeatureSupported(feature)
}

data class MapperWebViewFeatureState(
    val forceDarkStrategy: Boolean,
    val algorithmicDarkening: Boolean,
    val rendererClient: Boolean,
)

class MapperWebViewFeatures(
    private val checker: MapperWebViewFeatureChecker = AndroidxMapperWebViewFeatureChecker,
) {
    fun state(): MapperWebViewFeatureState {
        return MapperWebViewFeatureState(
            forceDarkStrategy = checker.isSupported(FORCE_DARK_STRATEGY),
            algorithmicDarkening = checker.isSupported(ALGORITHMIC_DARKENING),
            rendererClient = checker.isSupported(RENDERER_CLIENT),
        )
    }

    companion object {
        const val FORCE_DARK_STRATEGY: String = WebViewFeature.FORCE_DARK_STRATEGY
        const val ALGORITHMIC_DARKENING: String = WebViewFeature.ALGORITHMIC_DARKENING
        const val RENDERER_CLIENT: String = WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE
    }
}
