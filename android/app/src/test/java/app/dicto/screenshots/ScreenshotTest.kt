package app.dicto.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.dicto.ime.KeyboardPanel
import app.dicto.triggers.DictationPanel
import app.dicto.ui.DictoRoot
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rend les vrais écrans de l'app en PNG (build/screenshots/), au format du canvas
 * (390 × 844 dp), pour les comparer au design sans téléphone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Langue et thème des captures : `-Pdicto.screenshotLang=en|fr`, `-Pdicto.screenshotTheme=light|dark`. */
    private val lang = System.getProperty("dicto.screenshotLang")?.takeIf { it == "en" } ?: "fr"
    private val dark = System.getProperty("dicto.screenshotTheme") == "dark"
    private fun t(fr: String, en: String) = if (lang == "en") en else fr

    /**
     * Un état d'exemple : la question des mises à jour déjà répondue, un modèle choisi, une dictée en cours
     * et quelques dictées dans l'historique.
     */
    @org.junit.Before
    fun seed() {
        val d = app.dicto.DictoApp.instance
        app.dicto.i18n.I18n.override = lang
        app.dicto.update.UpdateChecker.setEnabled(d, false)
        d.settings.setMode(uniffi.dicto_ffi.Mode.CONTINUOUS)
        d.settings.setEngine(app.dicto.data.EngineChoice.Local("parakeet-tdt-0.6b-v3-int8"))
        // Le chargement (impossible ici : pas de modèle) se termine avant qu'on pose l'exemple.
        val deadline = System.currentTimeMillis() + 3000
        while (d.dictation.ui.value.engine.kind == app.dicto.dictation.EngineStatus.Kind.LOADING && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        val history = listOf(
            t("Bonjour à tous, voici le compte rendu de la réunion de ce matin.", "Hi everyone, here are the notes from this morning's meeting.") to 5200uL,
            t("Penser à rappeler le garage avant 18 h pour le contrôle technique.", "Remember to call the garage back before 6 pm about the MOT.") to 3900uL,
            t("Liste de courses : tomates, feta, huile d'olive, citrons et basilic.", "Shopping list: tomatoes, feta, olive oil, lemons and basil.") to 4600uL,
            t("Je serai en retard d'un quart d'heure, commencez sans moi.", "I'll be fifteen minutes late, start without me.") to 2800uL,
        )
        for ((text, ms) in history) {
            runCatching {
                d.storage.addHistory(
                    uniffi.dicto_ffi.DictationResult(text, emptyList(), text, lang, "Parakeet v3", ms, null, false),
                    "app.dicto",
                )
            }
        }
        d.dictation.showSample(
            app.dicto.dictation.DictationUi(
                mode = uniffi.dicto_ffi.Mode.CONTINUOUS,
                state = uniffi.dicto_ffi.State.LISTENING,
                startedAt = android.os.SystemClock.elapsedRealtime() - 42_000,
                chunks = listOf(t("Bonjour à tous, voici le compte rendu de la réunion de ce matin.", "Hi everyone, here are the notes from this morning's meeting.")),
                partial = t("On commence par le planning de la semaine", "Let's start with this week's schedule"),
                engine = app.dicto.dictation.EngineStatus(app.dicto.dictation.EngineStatus.Kind.READY, "Parakeet v3", "sur le téléphone"),
            ),
        )
    }

    /** build/screenshots/<langue>[-dark]/<nom>.png */
    private fun shot(name: String) =
        compose.onRoot().captureRoboImage("build/screenshots/$lang${if (dark) "-dark" else ""}/$name.png")

    @Test
    fun mainScreens() {
        compose.setContent { DictoTheme(dark = dark) { DictoRoot() } }
        compose.waitForIdle()
        shot("01-dictee")
        compose.onNodeWithText(app.dicto.i18n.tr("Modèles")).performClick()
        compose.waitForIdle()
        shot("02-modeles")
        compose.onNodeWithText("Canary 180M").performClick()
        compose.waitForIdle()
        shot("02b-modeles-details")
        compose.onNodeWithText(app.dicto.i18n.tr("En ligne")).performClick()
        compose.waitForIdle()
        shot("02c-modeles-en-ligne")
        compose.onNodeWithText(app.dicto.i18n.tr("Déclencheurs")).performClick()
        compose.waitForIdle()
        shot("03-declencheurs")
        compose.onNodeWithText(app.dicto.i18n.tr("Historique")).performClick()
        compose.waitForIdle()
        shot("04-historique")
    }

    @Test
    fun settingsAndFiles() {
        compose.setContent { DictoTheme(dark = dark) { DictoRoot() } }
        compose.waitForIdle()
        compose.onNodeWithText(app.dicto.i18n.tr("Fichier audio")).performClick()
        compose.waitForIdle()
        shot("05-fichiers")
    }

    @Test
    fun credits() {
        compose.setContent { DictoTheme(dark = dark) { app.dicto.ui.CreditsScreen(onBack = {}) } }
        compose.waitForIdle()
        shot("07-credits")
    }

    @Test
    fun providers() {
        compose.setContent {
            DictoTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(Dicto.Background).padding(20.dp)) {
                    androidx.compose.material3.Text(app.dicto.i18n.tr("Retouche par IA"), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    app.dicto.ui.RefineSettingsCard()
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    app.dicto.ui.providers.TranscriptionProviders()
                }
            }
        }
        compose.waitForIdle()
        shot("08-fournisseurs")
    }

    @Test
    fun providerSheet() {
        compose.setContent {
            DictoTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(Color(0xFF8A847D)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                    androidx.compose.material3.Surface(color = Dicto.Surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                        Column(Modifier.padding(top = 24.dp)) {
                            app.dicto.ui.providers.ProviderSheetContent(
                                app.dicto.ui.providers.refineSpec(uniffi.dicto_ffi.RefineProvider.ANTHROPIC), onSaved = {}, onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        shot("09-panneau-fournisseur")
    }

    @Test
    fun modelPicker() {
        val models = listOf(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-2", "claude-opus-5",
            "claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5",
        )
        compose.setContent {
            DictoTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8A847D)).padding(12.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Surface(color = Dicto.Surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                        app.dicto.ui.providers.ModelPickerContent(models, null, "claude-opus-5-5", true, onRetry = {}, onPick = {}, onClose = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        shot("10-choix-modele")
    }

    @Test
    fun overlays() {
        // La bulle montre son propre texte : pas de phrase en cours en plus.
        app.dicto.DictoApp.instance.dictation.let { it.showSample(it.ui.value.copy(partial = "")) }
        compose.setContent {
            DictoTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(Color(0xFFEEF0F3))) {
                    Box(Modifier.weight(1f).padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                        DictationPanel(
                            text = t("Vers 19 h 30, je ramène le dessert", "I'll bring dessert around 7:30"),
                            onCancel = {}, onInsert = {}, onMicPress = {}, onMicRelease = {},
                        )
                    }
                    KeyboardPanel(
                        hasMicPermission = true,
                        onPress = {}, onRelease = {}, onBackspace = {}, onEnter = {}, onSpace = {},
                        onSwitchKeyboard = {}, onOpenApp = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        shot("06-panneau-et-clavier")
    }
}

@Suppress("unused")
private val keep = Dicto.Background
