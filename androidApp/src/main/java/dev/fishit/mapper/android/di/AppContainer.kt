package dev.fishit.mapper.android.di

import android.content.Context
import dev.fishit.mapper.android.capture.CaptureSessionManager
import dev.fishit.mapper.android.capture.CaptureStorageManager
import dev.fishit.mapper.android.capture.SessionToEngineAdapter
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.export.ExportManager
import dev.fishit.mapper.android.import.ImportManager
import dev.fishit.mapper.android.import.httpcanary.HttpCanaryImportManager
import dev.fishit.mapper.engine.MappingEngine

/**
 * Dependency container for the FishIT-Mapper app.
 *
 * Enthält alle Dependencies für:
 * - Projekt-Management (store, mappingEngine)
 * - Import/Export (exportManager, importManager, httpCanaryImportManager)
 * - Traffic Capture (captureSessionManager, captureStorageManager)
 */
class AppContainer(context: Context) {
    // Projekt-Management
    val store: AndroidProjectStore = AndroidProjectStore(context)
    val mappingEngine: MappingEngine = MappingEngine()

    // Import/Export
    val exportManager: ExportManager = ExportManager(context, store)
    val importManager: ImportManager = ImportManager(context, store)
    val httpCanaryImportManager: HttpCanaryImportManager = HttpCanaryImportManager(context, store)

    // Traffic Capture (NEU)
    val captureSessionManager: CaptureSessionManager = CaptureSessionManager(context)
    val captureStorageManager: CaptureStorageManager = CaptureStorageManager(context)
    val sessionToEngineAdapter: SessionToEngineAdapter = SessionToEngineAdapter()
}
