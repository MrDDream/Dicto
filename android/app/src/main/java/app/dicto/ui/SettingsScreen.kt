package app.dicto.ui

import app.dicto.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.dicto.dictation.userMessage
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
    val spokenPunctuation by app.settings.spokenPunctuation.flow.collectAsStateWithLifecycle()
    val removeFillers by app.settings.removeFillers.flow.collectAsStateWithLifecycle()
    val vibrate by app.settings.vibrate.flow.collectAsStateWithLifecycle()
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
                SettingRow(tr("Commandes vocales"), tr("« nouvelle ligne », « efface ça », « majuscule », « tout en majuscules », « envoie le message »")) {
                    DictoSwitch(voiceCommands, app.settings::setVoiceCommands, tr("Commandes vocales"))
                }
                SettingRow(tr("Ponctuation dictée"), tr("« virgule », « point », « point d'interrogation », « ouvrez les guillemets »…")) {
                    DictoSwitch(spokenPunctuation, app.settings.spokenPunctuation::set, tr("Ponctuation dictée"))
                }
                SettingRow(tr("Retirer les hésitations"), tr("« euh », « hum » et les mots répétés par erreur")) {
                    DictoSwitch(removeFillers, app.settings.removeFillers::set, tr("Retirer les hésitations"))
                }
                ParagraphsSetting()
                SettingRow(
                    tr("Texte en direct"),
                    tr("Affiche la phrase pendant que tu parles, avec les modèles rapides. Consomme un peu plus de batterie."),
                ) {
                    DictoSwitch(livePreview, app.settings.livePreview::set, tr("Texte en direct"))
                }
                SettingRow(tr("Vibrer au début et à la fin"), tr("Pour savoir sans regarder que Dicto t'écoute.")) {
                    DictoSwitch(vibrate, app.settings.vibrate::set, tr("Vibrer au début et à la fin"))
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

            SectionTitle(tr("Remplacements"), tr("Dis l'expression, Dicto écrit le texte. Ex. « mon adresse » → ton adresse complète."))
            ReplacementsCard()

            SectionTitle(tr("Retouche par IA"), tr("Ponctuation, style, traduction."))
            RefineSettingsCard()

            SectionTitle(tr("Profils par app"), tr("Langue, retouche et mode selon l'app où tu écris."))
            ProfilesCard()

            SectionTitle(tr("Sauvegarde"), tr("Vocabulaire, remplacements et profils, pour les retrouver sur un autre appareil (téléphone ou ordinateur)."))
            BackupCard()

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

/** Remplacements personnels : « expression → texte ». */
@Composable
private fun ReplacementsCard() {
    val app = DictoApp.instance
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh) { runCatching { app.storage.replacements() }.getOrDefault(emptyList()) }
    var phrase by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    fun add() {
        if (phrase.isBlank() || text.isBlank()) return
        runCatching { app.storage.setReplacement(phrase.trim(), text) }
        phrase = ""
        text = ""
        refresh++
        app.dictation.applyOptions()
    }
    DictoCard {
        for (r in list) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.phrase, style = MaterialTheme.typography.titleSmall)
                    Text("→ " + r.replacement, style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted)
                }
                GhostButton(null, DictoIcons.Close, {
                    runCatching { app.storage.removeReplacement(r.id) }
                    refresh++
                    app.dictation.applyOptions()
                }, contentDescription = tr("Retirer %1\$s", r.phrase), tint = Dicto.InkMuted)
            }
        }
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Expression dite")) },
            placeholder = { Text(tr("mon adresse")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Texte écrit")) },
            placeholder = { Text(tr("12 rue des Lilas, 69000 Lyon")) },
            minLines = 1,
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
        )
        TonalButton(tr("Ajouter"), ::add, icon = DictoIcons.Plus)
    }
}

/** Export et import du vocabulaire, des remplacements et des profils (fichier JSON). */
@Composable
private fun BackupCard() {
    val app = DictoApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = runCatching {
            val json = app.storage.exportBundle()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            tr("Fichier enregistré.")
        }.getOrElse { tr("Export impossible : %1\$s", it.message ?: "") }
    }
    val import = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
            val c = app.storage.importBundle(json)
            app.dictation.applyOptions()
            tr("Importé : %1\$s mots, %2\$s remplacements, %3\$s profils.", c.vocabulary, c.replacements, c.profiles)
        }.getOrElse { tr("Import impossible : %1\$s", it.userMessage()) }
    }
    DictoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton(tr("Exporter"), { export.launch("dicto-export.json") }, icon = DictoIcons.Share)
            TonalButton(tr("Importer"), { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted) }
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
