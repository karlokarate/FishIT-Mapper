package info.plateaukao.einkbro.mapper.missiondock.controller

import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry

interface GuidedCaptureDockHost {
    fun runStepStart()
    fun runStepReady()
    fun runStepCheck()
    fun runStepPauseOrHold()
    fun runStepNextOrUndo()
    fun runStepRetry()
    fun runStepSkipOptional()
    fun runStepFinish()
    fun runExportSummary()
    fun runAnchorCreate()
    fun runAnchorLabel()
    fun runAnchorRemove()

    fun selectEndpoint(role: String, endpointId: String, score: Double)
    fun excludeEndpoint(endpointId: String, excluded: Boolean)
    fun testEndpoint(candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate, onFinished: () -> Unit = {})

    fun copyToClipboard(label: String, value: String)
    fun toast(message: String)
}
