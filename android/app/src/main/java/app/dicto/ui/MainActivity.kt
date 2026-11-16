package app.dicto.ui

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
    Models("Modèles", DictoIcons.Models),
    Triggers("Déclencheurs", DictoIcons.Triggers),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { DictoTheme { DictoRoot() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Fichiers partagés depuis une autre app : mis en file de transcription. */
    private fun handleShare(intent: android.content.Intent?) {
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
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    val shareRequest by MainActivity.openFiles.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(shareRequest) {
        if (shareRequest) {
            filesOpen = true
            MainActivity.openFiles.value = false
        }
    }

    BackHandler(enabled = settingsOpen || filesOpen || tab != Tab.Dictation) {
        when {
            filesOpen -> filesOpen = false
            settingsOpen -> settingsOpen = false
            else -> tab = Tab.Dictation
        }
    }

    Column(Modifier.fillMaxSize().background(Dicto.Background)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            if (filesOpen) {
                FilesScreen(onBack = { filesOpen = false })
            } else if (settingsOpen) {
                SettingsScreen(onBack = { settingsOpen = false })
            } else {
                when (tab) {
                    Tab.Dictation -> DictationScreen(
                        onOpenSettings = { settingsOpen = true },
                        onOpenModels = { tab = Tab.Models },
                        onOpenFiles = { filesOpen = true },
                    )
                    Tab.History -> HistoryScreen()
                    Tab.Models -> ModelsScreen()
                    Tab.Triggers -> TriggersScreen()
                }
            }
        }
        if (!settingsOpen && !filesOpen) BottomNav(tab) { tab = it }
    }
    app.dicto.update.UpdateDialogs()
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(Dicto.Surface).navigationBarsPadding()) {
        HorizontalDivider(color = Dicto.SurfaceSunken)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            for (tab in Tab.entries) {
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
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
