package dev.fishit.mapper.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import dev.fishit.mapper.android.di.AppContainer

class MainActivity : ComponentActivity() {
    
    // State to track Custom Tab return
    private val customTabReturnState = mutableStateOf<CustomTabReturn?>(null)
    
    data class CustomTabReturn(
        val returnedAt: Long,
        val fromUrl: String?
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = AppContainer(applicationContext)
        
        // Check if launched from Custom Tab return
        handleIntent(intent)

        setContent {
            FishitApp(
                container = container,
                customTabReturnState = customTabReturnState
            )
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // Detect return from Custom Tab (when app comes to foreground)
        if (customTabReturnState.value == null) {
            // This could be a return from Custom Tab without deep link
            Log.d(TAG, "Activity resumed - possible Custom Tab return")
        }
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "fishit" && uri.host == "auth-callback") {
                Log.d(TAG, "Custom Tab callback received: $uri")
                customTabReturnState.value = CustomTabReturn(
                    returnedAt = System.currentTimeMillis(),
                    fromUrl = uri.toString()
                )
            }
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}
