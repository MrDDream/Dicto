package app.dicto.triggers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.dicto.DictoApp
import app.dicto.dictation.DictationTarget
import app.dicto.dictation.applyEdits
import app.dicto.dictation.joinChunks
import app.dicto.ime.KeyboardLink
import app.dicto.ui.MainActivity
import app.dicto.ui.theme.DictoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.Mode

/**
 * Dictée rapide par-dessus l'app en cours (tuile, geste depuis le bord, assistant, « OK Dicto ») :
 * un panneau translucide qui écoute dès l'ouverture puis écrit le texte dans l'app
 * d'origine par le clavier Dicto, ou le copie.
 */
class QuickDictateActivity : ComponentActivity(), DictationTarget {
    private val controller get() = DictoApp.instance.dictation
    private var chunks by mutableStateOf(emptyList<String>())
    private var targetPackage: String? = null
    private var finishing = false

    override val targetApp: String? get() = targetPackage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        targetPackage = KeyboardLink.lastApp
        controller.target = this
        setContent {
            DictoTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { cancelAndFinish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    DictationPanel(
                        text = joinChunks(chunks),
                        onCancel = ::cancelAndFinish,
                        onInsert = ::insertAndFinish,
                        onMicPress = controller::press,
                        onMicRelease = controller::release,
                        modifier = Modifier.navigationBarsPadding().imePadding().padding(12.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
            }
        }
        if (savedInstanceState == null) start()
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Sans permission, on ouvre Dicto qui la demandera.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (controller.ui.value.isActive) return
        // Sans les mains : le mode maintenu n'a pas de sens ici, on passe en marche/arrêt.
        val mode = DictoApp.instance.settings.mode.value
        controller.pressWithMode(if (mode == Mode.HOLD) Mode.PUSH_TO_TALK else mode)
        // Marche/arrêt : dès que la phrase est transcrite, on insère et on ferme.
        if (controller.ui.value.isActive && mode != Mode.CONTINUOUS) {
            lifecycleScope.launch {
                controller.ui.first { it.isActive }
                controller.ui.first { !it.isActive && it.pending == 0 }
                if (chunks.isNotEmpty()) insertAndFinish()
            }
        }
    }

    /** Relancée pendant l'écoute (geste, tuile) : arrêt. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (controller.ui.value.isActive) toggle()
    }

    /** Second déclenchement pendant l'écoute : arrêt. */
    fun toggle() {
        controller.press()
        controller.release()
    }

    override fun deliver(result: DictationResult) {
        chunks = applyEdits(chunks, result.edits)
    }

    private fun insertAndFinish() {
        if (finishing) return
        if (controller.ui.value.isActive || controller.ui.value.pending > 0) {
            // Encore à l'écoute (mode continu) : on arrête, on attend la dernière phrase, puis on insère.
            if (controller.ui.value.isActive) toggle()
            lifecycleScope.launch {
                controller.ui.first { !it.isActive && it.pending == 0 }
                insertAndFinish()
            }
            return
        }
        finishing = true
        val text = joinChunks(chunks)
        if (KeyboardLink.isCurrent(this)) {
            // Clavier Dicto en cours : on écrit une fois le champ d'origine revenu au premier plan.
            val app = applicationContext
            finish()
            Handler(Looper.getMainLooper()).postDelayed({ TextInserter.insert(app, text) }, 300)
        } else {
            // Le choix du clavier doit s'ouvrir depuis une fenêtre active : avant de fermer.
            TextInserter.insert(this, text)
            finish()
        }
    }

    private fun cancelAndFinish() {
        if (finishing) return
        finishing = true
        if (controller.ui.value.isActive) controller.cancel()
        finish()
    }

    override fun onDestroy() {
        if (controller.target === this) controller.target = null
        if (current === this) current = null
        if (!finishing && controller.ui.value.isActive && isFinishing) controller.cancel()
        WakeWordService.resume()
        super.onDestroy()
    }

    companion object {
        @Volatile var current: QuickDictateActivity? = null
            private set

        /** Ouvre la dictée rapide, ou l'arrête si elle écoute déjà. */
        fun launch(context: Context, source: String) {
            current?.let {
                it.toggle()
                return
            }
            context.startActivity(
                Intent(context, QuickDictateActivity::class.java)
                    .putExtra("source", source)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }
}
