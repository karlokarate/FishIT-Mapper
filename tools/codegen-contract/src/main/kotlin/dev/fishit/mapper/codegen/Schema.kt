package dev.fishit.mapper.codegen

import kotlinx.serialization.Serializable

@Serializable
data class ContractSchema(
    val contractVersion: String,
    val packageName: String,
    val appName: String,
    val nodeKinds: List<String>,
    val edgeKinds: List<String>,
    val resourceKinds: List<String>,
    val consoleLevels: List<String>,
    val export: ExportSchema
)

@Serializable
data class ExportSchema(
    val bundleFormatVersion: String,
    val classDiscriminator: String
)
