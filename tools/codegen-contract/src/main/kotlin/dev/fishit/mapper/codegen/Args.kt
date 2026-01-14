package dev.fishit.mapper.codegen

data class Args(
    val schemaPath: String,
    val outDirPath: String
) {
    companion object {
        fun parse(args: List<String>): Args {
            fun valueAfter(flag: String): String {
                val idx = args.indexOf(flag)
                require(idx != -1 && idx + 1 < args.size) { "Missing value for ${'$'}flag" }
                return args[idx + 1]
            }

            val schema = valueAfter("--schema")
            val out = valueAfter("--out")

            return Args(schemaPath = schema, outDirPath = out)
        }
    }
}
