package app.dicto.ui

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.data.Journal
import app.dicto.BuildConfig
import app.dicto.DictoApp
import app.dicto.data.LANGUAGES
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons

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
        ScreenHeader("Réglages", onBack)
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Langue", "« Auto » détecte la langue à chaque phrase.")
            DictoCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((code, name) in LANGUAGES) {
                        ChipButton(onClick = { app.settings.setLanguage(code) }, accent = code == language) {
                            Text(name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            SectionTitle("Pendant la dictée")
            DictoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                SettingRow("Commandes vocales", "« nouvelle ligne », « nouveau paragraphe », « efface ça »") {
                    DictoSwitch(voiceCommands, app.settings::setVoiceCommands, "Commandes vocales")
                }
                SettingRow(
                    "Texte en direct",
                    "Affiche la phrase pendant que tu parles, avec les modèles rapides. Consomme un peu plus de batterie.",
                ) {
                    DictoSwitch(livePreview, app.settings.livePreview::set, "Texte en direct")
                }
            }

            SectionTitle("Clavier Dicto")
            DictoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                SettingRow("Écouter dès l'ouverture", "Passe sur le clavier Dicto et parle, sans toucher le micro.") {
                    DictoSwitch(imeAutoStart, app.settings.imeAutoStart::set, "Écouter dès l'ouverture")
                }
                SettingRow("Revenir à mon clavier", "Une fois la dictée arrêtée et le texte écrit, ton clavier habituel revient.") {
                    DictoSwitch(imeAutoReturn, app.settings.imeAutoReturn::set, "Revenir à mon clavier")
                }
            }

            SectionTitle("Vocabulaire", "Noms propres et mots techniques que le modèle doit connaître.")
            DictoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ajouter un mot") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTerm() }),
                    )
                    TonalButton("Ajouter", ::addTerm, icon = DictoIcons.Plus)
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
                                trailingIcon = { Icon(DictoIcons.Close, "Retirer ${t.term}", Modifier.padding(2.dp)) },
                            )
                        }
                    }
                }
            }

            SectionTitle("Retouche par IA", "Ponctuation, style, traduction.")
            RefineSettingsCard()

            SectionTitle("Profils par app", "Langue, retouche et mode selon l'app où tu écris.")
            ProfilesCard()

            SectionTitle("Diagnostic", "Les derniers événements du clavier et de la bulle, gardés sur le téléphone.")
            DictoCard {
                val context = androidx.compose.ui.platform.LocalContext.current
                val crashed = remember(refresh) { Journal.hasCrash() }
                Text(
                    if (crashed) "Dicto a planté récemment : le détail est dans le journal."
                    else "Si quelque chose ne marche pas, copie le journal et colle-le dans ton message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (crashed) Dicto.OnRecordingContainer else Dicto.InkMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("Copier le journal", {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Journal Dicto", Journal.export()))
                        android.widget.Toast.makeText(context, "Journal copié", android.widget.Toast.LENGTH_SHORT).show()
                    })
                    TonalButton("Effacer", {
                        Journal.clear()
                        refresh++
                    })
                }
            }

            SectionTitle("À propos")
            DictoCard {
                Text("Dicto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Transcription locale par sherpa-onnx et whisper.cpp. Modèles de NVIDIA, Meta, OpenAI et Bofeng Huang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dicto.InkMuted,
                )
                TonalButton("Crédits et licences", { creditsOpen = true })
            }
        }
    }
}
