package dev.fishit.mapper.android.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Minimal ViewModel factory to keep MVP code fast and dependency-free.
 */
class SimpleVmFactory<T : ViewModel>(
    private val create: () -> T
) : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        @Suppress("UNCHECKED_CAST")
        return create() as V
    }
}
