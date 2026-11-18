package app.dicto.ui

import app.dicto.i18n.tr
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import app.dicto.ui.theme.DictoTheme

enum class Tab(val label: String, val icon: ImageVector) {
    Dictation("Dictée", DictoIcons.Mic),
    History("Historique", DictoIcons.History),
    /** Affiché seulement quand les vocaux WhatsApp sont activés (Réglages › Vocaux WhatsApp). */
    WhatsApp("WhatsApp", DictoIcons.Wave),
    Settings("Réglages", DictoIcons.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            DictoTheme {
                // Barres du système : icônes claires sur le thème sombre, foncées sur le clair.
                val dark = Dicto.palette.dark
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                DictoRoot()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Fichiers partagés depuis une autre app : mis en file de transcription. */
    private fun handleShare(intent: android.content.Intent?) {
        // Notification « Vocal transcrit » : ouvre l'écran Fichiers.
        if (intent?.getBooleanExtra(EXTRA_OPEN_FILES, false) == true) {
            intent.removeExtra(EXTRA_OPEN_FILES)
            openFiles.value = true
            return
        }
        val uris: List<android.net.Uri> = when (intent?.action) {
            android.content.Intent.ACTION_SEND ->
                listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java))
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        uris.forEach { app.dicto.files.FilesController.add(this, it) }
        openFiles.value = true
    }

    companion object {
        const val EXTRA_OPEN_FILES = "app.dicto.OPEN_FILES"

        /** Demande d'ouvrir la rubrique Vocaux WhatsApp des réglages. */
        val openWhatsApp = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Demande d'ouvrir l'écran Fichiers (partage reçu). */
        val openFiles = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    override fun onResume() {
        super.onResume()
        // Les services de premier plan ne peuvent démarrer que depuis l'avant-plan : on les relance ici.
        val settings = app.dicto.DictoApp.instance.settings
        // Une fois par jour au plus, si l'utilisateur l'a accepté.
        app.dicto.update.UpdateChecker.checkAtStart(this)
        if (settings.bubble.value || settings.edgeSwipe.value) app.dicto.triggers.BubbleService.refresh(this)
        if (settings.wakeWord.value && !app.dicto.triggers.WakeWordService.running &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            app.dicto.triggers.WakeWordService.start(this)
        }
    }
}

@Composable
fun DictoRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Dictation) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    val shareRequest by MainActivity.openFiles.collectAsStateWithLifecycle()
    val whatsApp by app.dicto.DictoApp.instance.settings.voiceNotesEnabled.flow.collectAsStateWithLifecycle()
    val whatsAppRequest by MainActivity.openWhatsApp.collectAsStateWithLifecycle()
    // Onglet retiré (option coupée) : retour à la dictée.
    androidx.compose.runtime.LaunchedEffect(whatsApp) { if (!whatsApp && tab == Tab.WhatsApp) tab = Tab.Dictation }
    // Dernier vocal demandé sans dossier accordé : la rubrique des réglages.
    androidx.compose.runtime.LaunchedEffect(whatsAppRequest) {
        if (whatsAppRequest) {
            filesOpen = false
            settingsPage = SettingsPage.WhatsApp
            tab = Tab.Settings
            MainActivity.openWhatsApp.value = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(shareRequest) {
        if (shareRequest) {
            filesOpen = true
            MainActivity.openFiles.value = false
        }
    }

    BackHandler(enabled = filesOpen || tab != Tab.Dictation) {
        if (filesOpen) filesOpen = false else tab = Tab.Dictation
    }

    Column(Modifier.fillMaxSize().background(Dicto.Background)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            if (filesOpen) {
                FilesScreen(onBack = { filesOpen = false })
            } else {
                when (tab) {
                    Tab.Dictation -> DictationScreen(
                        onOpenModels = {
                            settingsPage = SettingsPage.Models
                            tab = Tab.Settings
                        },
                        onOpenFiles = { filesOpen = true },
                    )
                    Tab.History -> HistoryScreen()
                    Tab.WhatsApp -> VoiceNotesScreen(onOpenSettings = {
                        settingsPage = SettingsPage.WhatsApp
                        tab = Tab.Settings
                    })
                    Tab.Settings -> SettingsScreen(settingsPage) { settingsPage = it }
                }
            }
        }
        if (!filesOpen) BottomNav(tab, Tab.entries.filter { it != Tab.WhatsApp || whatsApp }) {
            // Toucher « Réglages » depuis une rubrique ramène à l'accueil des réglages.
            if (it == Tab.Settings && tab == Tab.Settings) settingsPage = null
            tab = it
        }
    }
    app.dicto.update.UpdateDialogs()
}

@Composable
private fun BottomNav(current: Tab, tabs: List<Tab>, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(Dicto.Surface).navigationBarsPadding()) {
        HorizontalDivider(color = Dicto.SurfaceSunken)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            for (tab in tabs) {
                val selected = tab == current
                Surface(
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f).semantics { this.selected = selected },
                    color = Color.Transparent,
                    contentColor = if (selected) Dicto.Accent else Dicto.InkMuted,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        Modifier.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                                .background(if (selected) Dicto.AccentContainer else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            tr(tab.label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
