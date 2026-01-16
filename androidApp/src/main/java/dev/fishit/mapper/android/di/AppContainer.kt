package dev.fishit.mapper.android.di

import android.content.Context
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.export.ExportManager
import dev.fishit.mapper.android.import.ImportManager
import dev.fishit.mapper.android.import.httpcanary.HttpCanaryImportManager
import dev.fishit.mapper.engine.MappingEngine

/**
 * Dependency container for the FishIT-Mapper app.
 *
 * Note: No internal traffic capture (proxy/MITM) - traffic is captured externally
 * by HttpCanary and imported via ZIP files.
 */
class AppContainer(context: Context) {
    val store: AndroidProjectStore = AndroidProjectStore(context)
    val mappingEngine: MappingEngine = MappingEngine()
    val exportManager: ExportManager = ExportManager(context, store)
    val importManager: ImportManager = ImportManager(context, store)
    val httpCanaryImportManager: HttpCanaryImportManager = HttpCanaryImportManager(context, store)
}
