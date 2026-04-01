package dev.fishit.mapper.webkit.compat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapperWebViewFeaturesTest {
    @Test
    fun `state reflects runtime feature support`() {
        val checker = object : MapperWebViewFeatureChecker {
            override fun isSupported(feature: String): Boolean {
                return feature == MapperWebViewFeatures.FORCE_DARK_STRATEGY
            }
        }

        val state = MapperWebViewFeatures(checker).state()

        assertTrue(state.forceDarkStrategy)
        assertFalse(state.algorithmicDarkening)
        assertFalse(state.rendererClient)
    }
}
