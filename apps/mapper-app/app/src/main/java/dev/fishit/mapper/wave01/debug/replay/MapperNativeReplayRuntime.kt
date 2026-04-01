package dev.fishit.mapper.wave01.debug.replay

import android.content.Context
import dev.fishit.mapper.network.CronetMapperHttpClient
import dev.fishit.mapper.network.FallbackOkHttpMapperHttpClient
import dev.fishit.mapper.network.MapperNativeHttpClient
import dev.fishit.mapper.network.MapperNativeHttpRequest
import dev.fishit.mapper.network.MapperNativeHttpResponse

object MapperNativeReplayRuntime {
    private const val PREF_RUNTIME_SETTINGS = "mapper_toolkit_runtime_settings"
    private const val KEY_USE_CRONET_FOR_NATIVE_REPLAY = "network.use_cronet_native_replay"

    fun useCronetForNativeReplay(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_RUNTIME_SETTINGS, Context.MODE_PRIVATE)
        val direct = prefs.all[KEY_USE_CRONET_FOR_NATIVE_REPLAY]
        return when (direct) {
            is Boolean -> direct
            is String -> direct.toBooleanStrictOrNull() ?: true
            is Number -> direct.toInt() != 0
            else -> true
        }
    }

    fun execute(context: Context, request: MapperNativeHttpRequest): MapperNativeHttpResponse {
        return client(context).execute(request)
    }

    fun transportLabel(context: Context): String {
        return if (useCronetForNativeReplay(context)) "cronet" else "okhttp_fallback"
    }

    private fun client(context: Context): MapperNativeHttpClient {
        return if (useCronetForNativeReplay(context)) {
            CronetMapperHttpClient(context)
        } else {
            FallbackOkHttpMapperHttpClient()
        }
    }
}
