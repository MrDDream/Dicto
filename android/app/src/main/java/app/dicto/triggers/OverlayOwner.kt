package app.dicto.triggers

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.dicto.ui.theme.DictoTheme

/**
 * Cycle de vie minimal pour afficher du Compose dans une fenêtre flottante
 * (bulle, bande de geste), hors de toute activité.
 */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun composeView(context: Context, content: @Composable () -> Unit): View =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayOwner)
            setViewTreeSavedStateRegistryOwner(this@OverlayOwner)
            setContent { DictoTheme(content = content) }
        }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
