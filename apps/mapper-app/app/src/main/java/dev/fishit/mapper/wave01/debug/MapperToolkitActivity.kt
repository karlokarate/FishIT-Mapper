package dev.fishit.mapper.wave01.debug

import android.os.Bundle
import info.plateaukao.einkbro.activity.BrowserActivity

/**
 * Dedicated second activity for Mapper-Toolkit embedding.
 *
 * This activity keeps the runtime-toolkit entrypoint isolated so the same
 * integration contract can later be consumed by a host app (e.g. FishIT-Player)
 * without coupling to the default launcher activity behavior.
 */
class MapperToolkitActivity : BrowserActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        RuntimeToolkitTelemetry.beginUiAction(
            context = this,
            actionName = "mapper_toolkit_activity_launch",
            screenId = "mapper_toolkit",
            payload = mapOf(
                "source" to "activity",
                "activity" to "MapperToolkitActivity",
            ),
        )
        super.onCreate(savedInstanceState)
    }
}
