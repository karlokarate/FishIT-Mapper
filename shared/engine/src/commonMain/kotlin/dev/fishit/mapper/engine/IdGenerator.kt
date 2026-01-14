package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlin.random.Random

/**
 * Simple ID generator that works in commonMain (no java.util.UUID required).
 *
 * IDs are short-ish, URL-safe, and prefixed to make debugging easier.
 */
object IdGenerator {
    private fun hex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                append("0123456789abcdef"[i ushr 4])
                append("0123456789abcdef"[i and 0x0F])
            }
        }

    private fun id(prefix: String, bytes: Int = 12): String {
        val buf = ByteArray(bytes)
        Random.Default.nextBytes(buf)
        return "${'$'}prefix${'$'}{hex(buf)}"
    }

    fun newProjectId(): ProjectId = ProjectId(id("prj_"))
    fun newSessionId(): SessionId = SessionId(id("ses_"))
    fun newEventId(): EventId = EventId(id("evt_"))
    fun newNodeId(): NodeId = NodeId(id("n_"))
    fun newEdgeId(): EdgeId = EdgeId(id("e_"))
    fun newChainId(): ChainId = ChainId(id("ch_"))
    fun newChainPointId(): ChainPointId = ChainPointId(id("pt_"))
}
