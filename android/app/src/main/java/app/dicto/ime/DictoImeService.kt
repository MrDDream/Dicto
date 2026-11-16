package app.dicto.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.dicto.DictoApp
import app.dicto.data.Journal
import app.dicto.dictation.DictationTarget
import app.dicto.ui.MainActivity
import app.dicto.ui.theme.DictoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.TextEdit
import uniffi.dicto_ffi.renderEdits

/**
 * Clavier vocal : un panneau compact (style de la bulle du design) qui insère le texte
 * dicté dans le champ actif, quelle que soit l'app.
 */
class DictoImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, DictationTarget {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Longueurs des derniers textes insérés, pour « efface ça ». */
    private val inserted = ArrayDeque<Int>()

    private val scope = MainScope()
    private var session: Job? = null
    /** Du texte a été écrit depuis l'ouverture du clavier. */
    private var wrote = false

    override val targetApp: String?
        get() = currentInputEditorInfo?.packageName

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        move(Lifecycle.Event.ON_CREATE)
        KeyboardLink.ime = this
        Journal.add("clavier", "créé")
    }

    /**
     * Android peut encore appeler le clavier pendant sa destruction (onFinishInputView depuis
     * super.onDestroy) : aucun événement après ON_DESTROY, sinon Lifecycle lève une exception.
     */
    private fun move(event: Lifecycle.Event) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        runCatching { lifecycleRegistry.handleLifecycleEvent(event) }
            .onFailure { Journal.add("clavier", "cycle de vie $event refusé : ${it.message}") }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Journal.add("clavier", "relié à ${attribute?.packageName} (type ${attribute?.inputType}, reprise $restarting)")
        KeyboardLink.onStartInput(this, attribute?.packageName)
    }

    /** Relié à un vrai champ de texte d'une autre app (pas à une fenêtre de Dicto). */
    internal fun isBoundToOtherApp(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return currentInputConnection != null && info.packageName != packageName &&
            (info.inputType and EditorInfo.TYPE_MASK_CLASS) != EditorInfo.TYPE_NULL
    }

    /** Texte venu de la bulle ou de la dictée rapide. */
    internal fun writeText(text: String): Boolean = commitSpaced(text)

    /**
     * Relit le champ : cet appel attend la réponse de l'app, donc que le texte envoyé avant
     * soit traité. Dit si le texte s'y trouve bien (pour le journal).
     */
    internal fun confirmText(text: String): String {
        val ic = currentInputConnection ?: return "champ fermé"
        val before = ic.getTextBeforeCursor(text.length + 2, 0) ?: return "l'app ne répond pas à la relecture"
        return if (before.toString().trimEnd().endsWith(text.trim())) "présent dans le champ" else "absent du champ à la relecture"
    }

    /** Remet le clavier d'avant, après un passage éclair par Dicto pour écrire le texte. */
    internal fun switchBack() {
        if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod()
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DictoImeService)
            setViewTreeSavedStateRegistryOwner(this@DictoImeService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                DictoTheme {
                    KeyboardPanel(
                        hasMicPermission = hasMicPermission(),
                        onPress = { DictoApp.instance.dictation.press() },
                        onRelease = { DictoApp.instance.dictation.release() },
                        onBackspace = ::backspace,
                        onEnter = ::enter,
                        onSpace = { currentInputConnection?.commitText(" ", 1) },
                        onSwitchKeyboard = ::switchKeyboard,
                        onOpenApp = ::openApp,
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) inserted.clear()
        DictoApp.instance.dictation.target = this
        Journal.add("clavier", "affiché (reprise $restarting)")
        matchNavigationBar()
        move(Lifecycle.Event.ON_RESUME)
        if (!restarting) startSession()
    }

    /** Barre de navigation sous le clavier : même fond, icônes lisibles en clair comme en sombre. */
    @Suppress("DEPRECATION")
    private fun matchNavigationBar() {
        val w = window.window ?: return
        val dark = keyboardIsDark(this)
        w.navigationBarColor = keyboardBackground(dark).toArgb()
        androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightNavigationBars = !dark
    }

    /**
     * Ouvrir le clavier Dicto suffit pour dicter : il écoute tout de suite, et rend la main
     * au clavier habituel une fois le texte écrit (micro arrêté, dernière phrase transcrite).
     */
    private fun startSession() {
        session?.cancel()
        wrote = false
        // Passage éclair pour écrire le texte de la bulle : ni écoute ni retour automatiques.
        if (KeyboardLink.consumeFlash() || KeyboardLink.hasPending() || !isBoundToOtherApp()) return
        val settings = DictoApp.instance.settings
        val controller = DictoApp.instance.dictation
        session = scope.launch {
            if (settings.imeAutoStart.value && hasMicPermission() && !controller.ui.value.isActive) {
                // Le modèle peut encore se charger juste après le lancement de Dicto.
                withTimeoutOrNull(10_000) { controller.ui.first { it.engine.isReady } } ?: return@launch
                if (controller.target !== this@DictoImeService || controller.ui.value.isActive) return@launch
                val mode = settings.mode.value
                // Maintenu n'a pas de sens sans doigt posé : marche/arrêt à la place.
                controller.pressWithMode(if (mode == Mode.HOLD) Mode.PUSH_TO_TALK else mode)
            }
            while (true) {
                controller.ui.first { it.isActive }
                controller.ui.first { !it.isActive && it.pending == 0 }
                if (wrote && settings.imeAutoReturn.value && controller.target === this@DictoImeService) {
                    switchKeyboard()
                    return@launch
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Journal.add("clavier", "masqué")
        // Clavier masqué : on ne garde pas le micro ouvert sans interface visible.
        releaseMic()
        move(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    /** Clavier fermé ou remplacé (par Gboard…) : le micro ne doit jamais rester ouvert derrière. */
    override fun onWindowHidden() {
        releaseMic()
        super.onWindowHidden()
    }

    private fun releaseMic() {
        session?.cancel()
        val controller = DictoApp.instance.dictation
        if (controller.target === this) {
            if (controller.ui.value.isActive) controller.cancel()
            controller.target = null
        }
    }

    override fun onDestroy() {
        Journal.add("clavier", "détruit")
        scope.cancel()
        KeyboardLink.onDestroyed(this)
        releaseMic()
        move(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // --- DictationTarget ---------------------------------------------------------------

    override fun deliver(result: DictationResult) {
        val ic = currentInputConnection ?: return
        val rest = mutableListOf<TextEdit>()
        for (e in result.edits) {
            if (e is TextEdit.DeletePrevious && rest.isEmpty()) {
                inserted.removeLastOrNull()?.let { ic.deleteSurroundingText(it, 0) }
            } else {
                rest += e
            }
        }
        commitSpaced(renderEdits(rest))
        wrote = true
        // « envoie le message » : le bouton d'action du champ (Envoyer, OK…), sinon Entrée.
        if (rest.any { it is TextEdit.Send }) send()
    }

    private fun send() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Insère au curseur, avec une espace si le texte précédent n'en finit pas par une. */
    /** Faux si le champ n'accepte plus de texte (connexion fermée). */
    private fun commitSpaced(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (text.isBlank()) return true
        val before = ic.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before.last().isWhitespace() && !text.first().isWhitespace()
        val toInsert = if (needsSpace) " $text" else text
        if (!ic.commitText(toInsert, 1)) return false
        inserted.addLast(toInsert.length)
        while (inserted.size > 20) inserted.removeFirst()
        return true
    }

    // --- Touches -----------------------------------------------------------------------

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        inserted.clear()
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiline = info != null && (info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
