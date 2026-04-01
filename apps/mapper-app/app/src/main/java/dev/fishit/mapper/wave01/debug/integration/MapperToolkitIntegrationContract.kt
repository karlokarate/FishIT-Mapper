package dev.fishit.mapper.wave01.debug.integration

/**
 * Host/feature integration contract for embedding Mapper-Toolkit as a second activity.
 *
 * Contract is intentionally generic and website-agnostic so host apps can route any
 * runtime mapping scenario through the same intent surface.
 */
object MapperToolkitIntegrationContract {
    const val ACTION_OPEN_MAPPER_TOOLKIT = "dev.fishit.mapper.wave01.action.OPEN_MAPPER_TOOLKIT"

    const val EXTRA_CALLER = "caller"
    const val EXTRA_RUN_ID = "run_id"
    const val EXTRA_TRACE_ID = "trace_id"
    const val EXTRA_SCREEN_HINT = "screen_hint"
    const val EXTRA_TARGET_URL = "target_url"
}
