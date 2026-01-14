package dev.fishit.mapper.codegen

import java.io.File

/**
 * FishIT-Mapper contract generator.
 *
 * Usage:
 *   --schema <path/to/contract.schema.json>
 *   --out    <output/dir>
 */
fun main(args: Array<String>) {
    val parsed = Args.parse(args.toList())

    val schemaFile = File(parsed.schemaPath)
    require(schemaFile.exists()) { "Schema file does not exist: ${'$'}{schemaFile.absolutePath}" }

    val outDir = File(parsed.outDirPath)
    outDir.mkdirs()

    val schema = ContractSchemaLoader.load(schemaFile)
    ContractGenerator(schema).generateAll(outDir)

    println("Generated FishIT contract into: ${'$'}{outDir.absolutePath}")
}
