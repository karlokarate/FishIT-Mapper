package dev.fishit.mapper.codegen

import java.io.File
import kotlinx.serialization.json.Json

object ContractSchemaLoader {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun load(schemaFile: File): ContractSchema {
        val text = schemaFile.readText(Charsets.UTF_8)
        return json.decodeFromString(ContractSchema.serializer(), text)
    }
}
