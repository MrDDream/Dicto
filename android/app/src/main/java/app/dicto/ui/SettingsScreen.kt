package app.dicto.ui

import app.dicto.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.data.Journal
import app.dicto.BuildConfig
import app.dicto.DictoApp
import app.dicto.data.LANGUAGES
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var creditsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (creditsOpen) {
        androidx.activity.compose.BackHandler { creditsOpen = false }
        CreditsScreen(onBack = { creditsOpen = false })
        return
    }
    val app = DictoApp.instance
    val language by app.settings.language.collectAsStateWithLifecycle()
    val voiceCommands by app.settings.voiceCommands.collectAsStateWithLifecycle()
    val livePreview by app.settings.livePreview.flow.collectAsStateWithLifecycle()
    val imeAutoStart by app.settings.imeAutoStart.flow.collectAsStateWithLifecycle()
    val imeAutoReturn by app.settings.imeAutoReturn.flow.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val terms = remember(refresh) { runCatching { app.storage.vocabulary() }.getOrDefault(emptyList()) }
    var newTerm by remember { mutableStateOf("") }

    fun addTerm() {
        if (newTerm.isBlank()) return
        runCatching { app.storage.addTerm(newTerm) }
        newTerm = ""
        refresh++
        app.dictation.applyOptions()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Réglages"), onBack)
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(tr("Apparence"))
            AppearanceCard()

            SectionTitle(tr("Langue de la dictée"), tr("« Auto » détecte la langue à chaque phrase."))
            DictoCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((code, name) in LANGUAGES) {
                        ChipButton(onClick = { app.settings.setLanguage(code) }, accent = code == language) {
                            Text(tr(name), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            SectionTitle(tr("Pendant la dictée"))
            DictoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                SettingRow(tr("Commandes vocales"), tr("« nouvelle ligne », « nouveau paragraphe », « efface ça »")) {
                    DictoSwitch(voiceCommands, app.settings::setVoiceCommands, tr("Commandes vocales"))
                }
                ParagraphsSetting()
                SettingRow(
                    tr("Texte en direct"),
                    tr("Affiche la phrase pendant que tu parles, avec les modèles rapides. Consomme un peu plus de batterie."),
                ) {
                    DictoSwitch(livePreview, app.settings.livePreview::set, tr("Texte en direct"))
                }
            }

            SectionTitle(tr("Clavier Dicto"))
            DictoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                SettingRow(tr("Écouter dès l'ouverture"), tr("Passe sur le clavier Dicto et parle, sans toucher le micro.")) {
                    DictoSwitch(imeAutoStart, app.settings.imeAutoStart::set, tr("Écouter dès l'ouverture"))
                }
                SettingRow(tr("Revenir à mon clavier"), tr("Une fois la dictée arrêtée et le texte écrit, ton clavier habituel revient.")) {
                    DictoSwitch(imeAutoReturn, app.settings.imeAutoReturn::set, tr("Revenir à mon clavier"))
                }
            }

            SectionTitle(tr("Vocabulaire"), tr("Noms propres et mots techniques que le modèle doit connaître."))
            DictoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(tr("Ajouter un mot")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTerm() }),
                    )
                    TonalButton(tr("Ajouter"), ::addTerm, icon = DictoIcons.Plus)
                }
                if (terms.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in terms) {
                            InputChip(
                                selected = false,
                                onClick = {
                                    runCatching { app.storage.removeTerm(t.id) }
                                    refresh++
                                    app.dictation.applyOptions()
                                },
                                label = { Text(t.term) },
                                trailingIcon = { Icon(DictoIcons.Close, tr("Retirer %1\$s", t.term), Modifier.padding(2.dp)) },
                            )
                        }
                    }
                }
            }

            SectionTitle(tr("Retouche par IA"), tr("Ponctuation, style, traduction."))
            RefineSettingsCard()

            SectionTitle(tr("Profils par app"), tr("Langue, retouche et mode selon l'app où tu écris."))
            ProfilesCard()

            SectionTitle(tr("Diagnostic"), tr("Les derniers événements du clavier et de la bulle, gardés sur le téléphone."))
            DictoCard {
                val context = androidx.compose.ui.platform.LocalContext.current
                val crashed = remember(refresh) { Journal.hasCrash() }
                Text(
                    if (crashed) tr("Dicto a planté récemment : le détail est dans le journal.")
                    else tr("Si quelque chose ne marche pas, copie le journal et colle-le dans ton message."),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (crashed) Dicto.OnRecordingContainer else Dicto.InkMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton(tr("Copier le journal"), {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Journal Dicto", Journal.export()))
                        android.widget.Toast.makeText(context, tr("Journal copié"), android.widget.Toast.LENGTH_SHORT).show()
                    })
                    TonalButton(tr("Effacer"), {
                        Journal.clear()
                        refresh++
                    })
                }
            }

            SectionTitle(tr("Mises à jour"), tr("Les nouvelles versions publiées sur GitHub."))
            UpdatesCard()

            SectionTitle(tr("À propos"))
            DictoCard {
                Text("Dicto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Transcription locale par sherpa-onnx et whisper.cpp. Modèles de NVIDIA, Meta, OpenAI et Bofeng Huang."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Dicto.InkMuted,
                )
                TonalButton(tr("Crédits et licences"), { creditsOpen = true })
            }
        }
    }
}

/** Vérification automatique (une fois par jour) et « Vérifier maintenant ». */
@Composable
private fun UpdatesCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent by app.dicto.update.UpdateChecker.consent.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    DictoCard {
        SettingRow(tr("Vérifier automatiquement"), tr("Une fois par jour, Dicto regarde sur GitHub si une version plus récente existe.")) {
            DictoSwitch(consent == true, { app.dicto.update.UpdateChecker.setEnabled(context, it) }, tr("Vérifier automatiquement"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalButton(if (checking) tr("Vérification…") else tr("Vérifier maintenant"), {
                if (checking) return@TonalButton
                checking = true
                status = null
                scope.launch {
                    status = app.dicto.update.UpdateChecker.checkNow(context).fold(
                        { r -> if (r == null) tr("Dicto est à jour (%1\$s).", BuildConfig.VERSION_NAME) else tr("Dicto %1\$s est disponible.", r.tag) },
                        { e -> e.message ?: tr("Vérification impossible.") },
                    )
                    checking = false
                }
            })
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted) }
    }
}

/** Langue de l'app et thème : « Auto » suit le téléphone. */
/** Pauses qui ouvrent un paragraphe (ms, « 0 » = jamais). */
private val PARAGRAPH_PAUSES = listOf("0" to "Jamais", "1500" to "Après 1,5 s", "2500" to "Après 2,5 s", "4000" to "Après 4 s")

@Composable
private fun ParagraphsSetting() {
    val paragraphs = DictoApp.instance.settings.paragraphs
    val pause by paragraphs.flow.collectAsStateWithLifecycle()
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Paragraphes automatiques"), style = MaterialTheme.typography.titleSmall)
        Text(
            tr("Une pause marquée ouvre un nouveau paragraphe, entre deux phrases ou dans un long enregistrement."),
            style = MaterialTheme.typography.labelSmall,
            color = Dicto.InkMuted,
            fontWeight = FontWeight.Normal,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((ms, name) in PARAGRAPH_PAUSES) {
                ChipButton(onClick = { paragraphs.set(ms) }, accent = ms == pause) {
                    Text(tr(name), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard() {
    val settings = DictoApp.instance.settings
    val language by settings.appLanguage.flow.collectAsStateWithLifecycle()
    val theme by settings.theme.flow.collectAsStateWithLifecycle()
    DictoCard {
        Text(tr("Langue de l'app"), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, name) in listOf("auto" to tr("Auto"), "fr" to "Français", "en" to "English")) {
                ChipButton(onClick = {
                    settings.appLanguage.set(code)
                    app.dicto.i18n.I18n.setMode(code)
                }, accent = code == language) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(tr("Thème"), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, name) in listOf("auto" to tr("Auto"), "light" to tr("Clair"), "dark" to tr("Sombre"))) {
                ChipButton(onClick = {
                    settings.theme.set(code)
                    app.dicto.ui.theme.ThemeMode.set(code)
                }, accent = code == theme) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(tr("« Auto » suit les réglages du téléphone."), style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted)
    }
}
