package dev.fishit.mapper.android.di

import android.content.Context
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.export.ExportManager
import dev.fishit.mapper.android.import.ImportManager
import dev.fishit.mapper.engine.MappingEngine

class AppContainer(context: Context) {
    val store: AndroidProjectStore = AndroidProjectStore(context)
    val mappingEngine: MappingEngine = MappingEngine()
    val exportManager: ExportManager = ExportManager(context, store)
    val importManager: ImportManager = ImportManager(context, store)
}
