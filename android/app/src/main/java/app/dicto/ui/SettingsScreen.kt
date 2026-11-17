package app.dicto.ui

import app.dicto.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.data.Journal
import app.dicto.BuildConfig
import app.dicto.DictoApp
import app.dicto.data.LANGUAGES
import app.dicto.data.label
import app.dicto.dictation.EngineStatus
import app.dicto.dictation.userMessage
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.launch

/** Rubriques des réglages : chacune ouvre sa page. */
enum class SettingsPage(val title: String) {
    Models("Modèles"),
    Triggers("Déclencheurs"),
    Dictation("Dictée"),
    Words("Mots et remplacements"),
    Refine("Retouche par IA"),
    Profiles("Profils par app"),
    Keyboard("Clavier Dicto"),
    Appearance("Apparence"),
    Backup("Sauvegarde"),
    About("À propos"),
}

@Composable
fun SettingsScreen(page: SettingsPage?, onPage: (SettingsPage?) -> Unit) {
    var creditsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (creditsOpen) {
        androidx.activity.compose.BackHandler { creditsOpen = false }
        CreditsScreen(onBack = { creditsOpen = false })
        return
    }
    val current = page
    if (current == null) {
        SettingsHome(open = onPage)
        return
    }
    androidx.activity.compose.BackHandler { onPage(null) }
    // Modèles et Déclencheurs gardent leur propre écran, avec un retour vers les réglages.
    when (current) {
        SettingsPage.Models -> return ModelsScreen(onBack = { onPage(null) })
        SettingsPage.Triggers -> return TriggersScreen(onBack = { onPage(null) })
        else -> Unit
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr(current.title), onBack = { onPage(null) })
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (current) {
                SettingsPage.Dictation -> DictationPage()
                SettingsPage.Words -> WordsPage()
                SettingsPage.Refine -> RefineSettingsCard()
                SettingsPage.Profiles -> ProfilesCard()
                SettingsPage.Keyboard -> KeyboardPage()
                SettingsPage.Appearance -> AppearanceCard()
                SettingsPage.Backup -> BackupCard()
                SettingsPage.About -> AboutPage(openCredits = { creditsOpen = true })
                SettingsPage.Models, SettingsPage.Triggers -> Unit
            }
        }
    }
}

/** Accueil des réglages : les rubriques, avec un résumé de leur état. */
@Composable
private fun SettingsHome(open: (SettingsPage) -> Unit) {
    val app = DictoApp.instance
    val s = app.settings
    val language by s.language.collectAsStateWithLifecycle()
    val voiceCommands by s.voiceCommands.collectAsStateWithLifecycle()
    val spokenPunctuation by s.spokenPunctuation.flow.collectAsStateWithLifecycle()
    val removeFillers by s.removeFillers.flow.collectAsStateWithLifecycle()
    val paragraphs by s.paragraphs.flow.collectAsStateWithLifecycle()
    val refineProvider by s.refineProvider.collectAsStateWithLifecycle()
    val preset by s.refinePreset.flow.collectAsStateWithLifecycle()
    val translateTo by s.translateTo.flow.collectAsStateWithLifecycle()
    val imeAutoStart by s.imeAutoStart.flow.collectAsStateWithLifecycle()
    val imeAutoReturn by s.imeAutoReturn.flow.collectAsStateWithLifecycle()
    val appLanguage by s.appLanguage.flow.collectAsStateWithLifecycle()
    val ui by app.dictation.ui.collectAsStateWithLifecycle()
    val bubble by s.bubble.flow.collectAsStateWithLifecycle()
    val wake by s.wakeWord.flow.collectAsStateWithLifecycle()
    val edge by s.edgeSwipe.flow.collectAsStateWithLifecycle()
    val theme by s.theme.flow.collectAsStateWithLifecycle()
    val counts = remember {
        Triple(
            runCatching { app.storage.vocabulary().size }.getOrDefault(0),
            runCatching { app.storage.replacements().size }.getOrDefault(0),
            runCatching { app.storage.profiles().map { it.label ?: it.appId } }.getOrDefault(emptyList()),
        )
    }

    val dictation = listOfNotNull(
        tr(LANGUAGES.firstOrNull { it.first == language }?.second ?: "Auto"),
        tr("commandes vocales").takeIf { voiceCommands },
        tr("ponctuation dictée").takeIf { spokenPunctuation },
        tr("sans hésitations").takeIf { removeFillers },
        tr("paragraphes").takeIf { paragraphs != "0" },
    ).joinToString(" · ")
    val refine = refineProvider?.let { p ->
        listOfNotNull(
            p.label,
            uniffi.dicto_ffi.refinePresets().firstOrNull { it.id == preset }?.name?.let { tr(it) } ?: tr("Personnalisée").takeIf { preset == "custom" },
            translateTo.takeIf { it != "none" }?.let { "→ " + it.uppercase() },
        ).joinToString(" · ")
    } ?: tr("Désactivée")
    val keyboard = listOfNotNull(
        tr("écoute dès l'ouverture").takeIf { imeAutoStart },
        tr("retour à ton clavier").takeIf { imeAutoReturn },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifBlank { tr("Le micro s'ouvre quand tu le touches") }
    val engine = if (ui.engine.kind == EngineStatus.Kind.NONE) tr("Choisir un modèle") else ui.engine.label
    val triggers = listOfNotNull(
        tr("bouton flottant").takeIf { bubble },
        tr("à la voix").takeIf { wake },
        tr("geste depuis le bord").takeIf { edge },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifBlank { tr("Clavier, bulle, voix, gestes") }
    val themeName = when (theme) { "light" -> tr("Clair"); "dark" -> tr("Sombre"); else -> tr("Thème auto") }
    val appLanguageName = when (appLanguage) { "fr" -> "Français"; "en" -> "English"; else -> tr("Langue auto") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Réglages"))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavCard(
                Triple(SettingsPage.Models, DictoIcons.Models, engine),
                Triple(SettingsPage.Triggers, DictoIcons.Triggers, triggers),
                open = open,
            )
            Spacer(Modifier.height(8.dp))
            NavCard(
                Triple(SettingsPage.Dictation, DictoIcons.Mic, dictation),
                Triple(SettingsPage.Words, DictoIcons.Pencil, words(counts.first, counts.second)),
                Triple(SettingsPage.Refine, DictoIcons.Sparkle, refine),
                Triple(SettingsPage.Profiles, DictoIcons.Gear, counts.third.joinToString(", ").ifBlank { tr("Aucun profil") }),
                Triple(SettingsPage.Keyboard, DictoIcons.Keyboard, keyboard),
                open = open,
            )
            Spacer(Modifier.height(8.dp))
            NavCard(
                Triple(SettingsPage.Appearance, DictoIcons.Eye, "$appLanguageName · $themeName"),
                Triple(SettingsPage.Backup, DictoIcons.Download, tr("Exporter ou importer tes mots et profils")),
                Triple(SettingsPage.About, DictoIcons.Settings, tr("Dicto %1\$s · mises à jour, journal, crédits", BuildConfig.VERSION_NAME)),
                open = open,
            )
        }
    }
}

/** « 3 mots · 1 remplacement », ou une invitation quand les deux listes sont vides. */
private fun words(terms: Int, replacements: Int): String = listOfNotNull(
    terms.takeIf { it > 0 }?.let { if (it == 1) tr("1 mot") else tr("%1\$s mots", it) },
    replacements.takeIf { it > 0 }?.let { if (it == 1) tr("1 remplacement") else tr("%1\$s remplacements", it) },
).joinToString(" · ").ifBlank { tr("Noms propres, abréviations, textes tout prêts") }

@Composable
private fun NavCard(vararg rows: Triple<SettingsPage, androidx.compose.ui.graphics.vector.ImageVector, String>, open: (SettingsPage) -> Unit) {
    DictoCard(padding = PaddingValues(vertical = 4.dp)) {
        Column {
            rows.forEachIndexed { i, (page, icon, summary) ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 68.dp), color = Dicto.SurfaceSunken)
                NavRow(icon, tr(page.title), summary) { open(page) }
            }
        }
    }
}

/** Langue, nettoyage du texte, paragraphes, retours pendant l'écoute. */
@Composable
private fun DictationPage() {
    val app = DictoApp.instance
    val language by app.settings.language.collectAsStateWithLifecycle()
    val voiceCommands by app.settings.voiceCommands.collectAsStateWithLifecycle()
    val livePreview by app.settings.livePreview.flow.collectAsStateWithLifecycle()
    val spokenPunctuation by app.settings.spokenPunctuation.flow.collectAsStateWithLifecycle()
    val removeFillers by app.settings.removeFillers.flow.collectAsStateWithLifecycle()
    val vibrate by app.settings.vibrate.flow.collectAsStateWithLifecycle()
    val paragraphs = app.settings.paragraphs
    val pause by paragraphs.flow.collectAsStateWithLifecycle()

    SectionTitle(tr("Texte"))
    DictoCard {
        ChoiceRow(
            tr("Langue de la dictée"),
            tr("« Auto » détecte la langue à chaque phrase."),
            LANGUAGES.map { (code, name) -> code to tr(name) },
            language,
            app.settings::setLanguage,
        )
        SettingRow(tr("Commandes vocales"), tr("« nouvelle ligne », « efface ça », « majuscule », « tout en majuscules », « envoie le message »")) {
            DictoSwitch(voiceCommands, app.settings::setVoiceCommands, tr("Commandes vocales"))
        }
        SettingRow(tr("Ponctuation dictée"), tr("« virgule », « point », « point d'interrogation », « ouvrez les guillemets »…")) {
            DictoSwitch(spokenPunctuation, app.settings.spokenPunctuation::set, tr("Ponctuation dictée"))
        }
        SettingRow(tr("Retirer les hésitations"), tr("« euh », « hum » et les mots répétés par erreur")) {
            DictoSwitch(removeFillers, app.settings.removeFillers::set, tr("Retirer les hésitations"))
        }
        ChoiceRow(
            tr("Paragraphes automatiques"),
            tr("Une pause marquée ouvre un nouveau paragraphe."),
            PARAGRAPH_PAUSES.map { (ms, name) -> ms to tr(name) },
            pause,
            paragraphs::set,
        )
    }

    SectionTitle(tr("Pendant l'écoute"))
    DictoCard {
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
}

/** Vocabulaire à reconnaître et remplacements « expression → texte ». */
@Composable
private fun WordsPage() {
    val app = DictoApp.instance
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

    Spacer(Modifier.height(4.dp))
    SectionTitle(tr("Remplacements"), tr("Dis l'expression, Dicto écrit le texte. Ex. « mon adresse » → ton adresse complète."))
    ReplacementsCard()
}

@Composable
private fun KeyboardPage() {
    val app = DictoApp.instance
    val imeAutoStart by app.settings.imeAutoStart.flow.collectAsStateWithLifecycle()
    val imeAutoReturn by app.settings.imeAutoReturn.flow.collectAsStateWithLifecycle()
    DictoCard {
        SettingRow(tr("Écouter dès l'ouverture"), tr("Passe sur le clavier Dicto et parle, sans toucher le micro.")) {
            DictoSwitch(imeAutoStart, app.settings.imeAutoStart::set, tr("Écouter dès l'ouverture"))
        }
        SettingRow(tr("Revenir à mon clavier"), tr("Une fois la dictée arrêtée et le texte écrit, ton clavier habituel revient.")) {
            DictoSwitch(imeAutoReturn, app.settings.imeAutoReturn::set, tr("Revenir à mon clavier"))
        }
    }
}

/** Version, crédits, mises à jour et journal de diagnostic. */
@Composable
private fun AboutPage(openCredits: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    DictoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LogoMark(40.dp)
            Column {
                Text("Dicto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text(tr("Licence GPL-3.0"), style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
            }
        }
        Text(
            tr("Transcription locale par sherpa-onnx et whisper.cpp. Modèles de NVIDIA, Meta, OpenAI et Bofeng Huang."),
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
        TonalButton(tr("Crédits et licences"), openCredits)
    }

    Spacer(Modifier.height(4.dp))
    SectionTitle(tr("Mises à jour"), tr("Les nouvelles versions publiées sur GitHub."))
    UpdatesCard()

    Spacer(Modifier.height(4.dp))
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
        Text(
            tr("Vocabulaire, remplacements et profils, pour les retrouver sur un autre appareil (téléphone ou ordinateur)."),
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
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

/** Pauses qui ouvrent un paragraphe (ms, « 0 » = jamais). */
private val PARAGRAPH_PAUSES = listOf("0" to "Jamais", "1500" to "Après 1,5 s", "2500" to "Après 2,5 s", "4000" to "Après 4 s")

/** Langue de l'app et thème : « Auto » suit le téléphone. */
@Composable
private fun AppearanceCard() {
    val settings = DictoApp.instance.settings
    val language by settings.appLanguage.flow.collectAsStateWithLifecycle()
    val theme by settings.theme.flow.collectAsStateWithLifecycle()
    DictoCard {
        ChoiceRow(
            tr("Langue de l'app"),
            tr("« Auto » suit la langue du téléphone."),
            listOf("auto" to tr("Auto"), "fr" to "Français", "en" to "English"),
            language,
        ) { code ->
            settings.appLanguage.set(code)
            app.dicto.i18n.I18n.setMode(code)
        }
        ChoiceRow(
            tr("Thème"),
            tr("« Auto » suit le mode clair ou sombre du téléphone."),
            listOf("auto" to tr("Auto"), "light" to tr("Clair"), "dark" to tr("Sombre")),
            theme,
        ) { code ->
            settings.theme.set(code)
            app.dicto.ui.theme.ThemeMode.set(code)
        }
    }
}
