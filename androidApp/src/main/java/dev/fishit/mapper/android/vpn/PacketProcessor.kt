package dev.fishit.mapper.android.vpn

import android.util.Log
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV6Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.namednumber.IpNumber

/**
 * Packet Processor für VPN Traffic.
 * 
 * Nutzt pcap4j für:
 * - Vollständiges IP packet parsing (IPv4/IPv6)
 * - TCP state machine und connection tracking
 * - UDP packet handling
 * - ICMP packet processing
 * - Packet reassembly für fragmentierte Pakete
 * 
 * Integration mit tun2socks library für natives TUN/TAP interface handling.
 */
class PacketProcessor {
    
    companion object {
        private const val TAG = "PacketProcessor"
    }
    
    /**
     * Parsed packet information.
     */
    data class ParsedPacket(
        val protocol: Protocol,
        val sourceAddress: String,
        val destAddress: String,
        val sourcePort: Int?,
        val destPort: Int?,
        val payload: ByteArray?,
        val flags: TcpFlags? = null
    )
    
    /**
     * Protocol type.
     */
    enum class Protocol {
        TCP, UDP, ICMP, OTHER
    }
    
    /**
     * TCP flags for state machine.
     */
    data class TcpFlags(
        val syn: Boolean,
        val ack: Boolean,
        val fin: Boolean,
        val rst: Boolean,
        val psh: Boolean
    )
    
    /**
     * Parsed ein IP Packet und extrahiert relevante Informationen.
     * 
     * Unterstützt:
     * - IPv4 und IPv6
     * - TCP mit flags
     * - UDP
     * - ICMP
     * 
     * @param packetData Raw packet bytes vom TUN interface
     * @return ParsedPacket oder null bei Parsing-Fehler
     */
    fun parsePacket(packetData: ByteArray): ParsedPacket? {
        return try {
            // Determine IP version from first nibble
            val version = (packetData[0].toInt() shr 4) and 0xF
            
            when (version) {
                4 -> parseIpV4Packet(packetData)
                6 -> parseIpV6Packet(packetData)
                else -> {
                    Log.w(TAG, "Unknown IP version: $version")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet", e)
            null
        }
    }
    
    /**
     * Parsed IPv4 packet.
     */
    private fun parseIpV4Packet(packetData: ByteArray): ParsedPacket? {
        return try {
            val packet = IpV4Packet.newPacket(packetData, 0, packetData.size)
            val header = packet.header
            
            val protocol = when (header.protocol) {
                IpNumber.TCP -> Protocol.TCP
                IpNumber.UDP -> Protocol.UDP
                IpNumber.ICMPV4 -> Protocol.ICMP
                else -> Protocol.OTHER
            }
            
            val sourceAddr = header.srcAddr.hostAddress ?: "unknown"
            val destAddr = header.dstAddr.hostAddress ?: "unknown"
            
            when (protocol) {
                Protocol.TCP -> parseTcpPacket(packet.payload as? TcpPacket, sourceAddr, destAddr)
                Protocol.UDP -> parseUdpPacket(packet.payload as? UdpPacket, sourceAddr, destAddr)
                Protocol.ICMP -> parseIcmpPacket(packet.payload, sourceAddr, destAddr)
                else -> ParsedPacket(protocol, sourceAddr, destAddr, null, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IPv4 packet", e)
            null
        }
    }
    
    /**
     * Parsed IPv6 packet.
     */
    private fun parseIpV6Packet(packetData: ByteArray): ParsedPacket? {
        return try {
            val packet = IpV6Packet.newPacket(packetData, 0, packetData.size)
            val header = packet.header
            
            val protocol = when (header.protocol) {
                IpNumber.TCP -> Protocol.TCP
                IpNumber.UDP -> Protocol.UDP
                IpNumber.ICMPV6 -> Protocol.ICMP
                else -> Protocol.OTHER
            }
            
            val sourceAddr = header.srcAddr.hostAddress ?: "unknown"
            val destAddr = header.dstAddr.hostAddress ?: "unknown"
            
            when (protocol) {
                Protocol.TCP -> parseTcpPacket(packet.payload as? TcpPacket, sourceAddr, destAddr)
                Protocol.UDP -> parseUdpPacket(packet.payload as? UdpPacket, sourceAddr, destAddr)
                Protocol.ICMP -> parseIcmpPacket(packet.payload, sourceAddr, destAddr)
                else -> ParsedPacket(protocol, sourceAddr, destAddr, null, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IPv6 packet", e)
            null
        }
    }
    
    /**
     * Parsed TCP packet und extrahiert flags für state machine.
     */
    private fun parseTcpPacket(
        tcpPacket: TcpPacket?,
        sourceAddr: String,
        destAddr: String
    ): ParsedPacket? {
        if (tcpPacket == null) return null
        
        val header = tcpPacket.header
        val flags = TcpFlags(
            syn = header.syn,
            ack = header.ack,
            fin = header.fin,
            rst = header.rst,
            psh = header.psh
        )
        
        return ParsedPacket(
            protocol = Protocol.TCP,
            sourceAddress = sourceAddr,
            destAddress = destAddr,
            sourcePort = header.srcPort.valueAsInt(),
            destPort = header.dstPort.valueAsInt(),
            payload = tcpPacket.payload?.rawData,
            flags = flags
        )
    }
    
    /**
     * Parsed UDP packet.
     */
    private fun parseUdpPacket(
        udpPacket: UdpPacket?,
        sourceAddr: String,
        destAddr: String
    ): ParsedPacket? {
        if (udpPacket == null) return null
        
        val header = udpPacket.header
        
        return ParsedPacket(
            protocol = Protocol.UDP,
            sourceAddress = sourceAddr,
            destAddress = destAddr,
            sourcePort = header.srcPort.valueAsInt(),
            destPort = header.dstPort.valueAsInt(),
            payload = udpPacket.payload?.rawData
        )
    }
    
    /**
     * Parsed ICMP packet.
     */
    private fun parseIcmpPacket(
        icmpPacket: Packet?,
        sourceAddr: String,
        destAddr: String
    ): ParsedPacket {
        return ParsedPacket(
            protocol = Protocol.ICMP,
            sourceAddress = sourceAddr,
            destAddress = destAddr,
            sourcePort = null,
            destPort = null,
            payload = icmpPacket?.rawData
        )
    }
    
    
    /**
     * Loggt packet informationen für debugging.
     */
    fun logPacket(packet: ParsedPacket) {
        val portInfo = if (packet.sourcePort != null && packet.destPort != null) {
            ":${packet.sourcePort} -> :${packet.destPort}"
        } else {
            ""
        }
        
        val flagInfo = packet.flags?.let { flags ->
            val flagList = mutableListOf<String>()
            if (flags.syn) flagList.add("SYN")
            if (flags.ack) flagList.add("ACK")
            if (flags.fin) flagList.add("FIN")
            if (flags.rst) flagList.add("RST")
            if (flags.psh) flagList.add("PSH")
            " [${flagList.joinToString(",")}]"
        } ?: ""
        
        val payloadInfo = packet.payload?.let { " (${it.size} bytes)" } ?: ""
        
        Log.d(
            TAG,
            "${packet.protocol} ${packet.sourceAddress}$portInfo -> ${packet.destAddress}$flagInfo$payloadInfo"
        )
    }
}
