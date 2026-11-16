package app.dicto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dicto.DictoApp
import app.dicto.ui.theme.Dicto
import uniffi.dicto_ffi.Credit

private val LIBRARIES = listOf(
    Credit("sherpa-onnx", "Apache-2.0", "k2-fsa (Next-gen Kaldi) : moteurs Canary, Parakeet, Nemotron, Omnilingual, détection de voix et mot d'activation.", "https://github.com/k2-fsa/sherpa-onnx"),
    Credit("ONNX Runtime", "MIT", "Microsoft : exécution des modèles sherpa-onnx.", "https://github.com/microsoft/onnxruntime"),
    Credit("whisper.cpp et ggml", "MIT", "Georgi Gerganov et contributeurs : moteur Whisper.", "https://github.com/ggml-org/whisper.cpp"),
    Credit("UniFFI", "MPL-2.0", "Mozilla : pont entre le cœur Rust et Kotlin.", "https://github.com/mozilla/uniffi-rs"),
    Credit("JNA", "Apache-2.0", "Java Native Access : appel du cœur Rust depuis Android.", "https://github.com/java-native-access/jna"),
    Credit("Bibliothèques Rust", "MIT / Apache-2.0", "tokio, reqwest, rustls, serde, hound, tar, bzip2 et leurs dépendances ; SQLite (domaine public) via rusqlite.", "https://crates.io"),
    Credit("Jetpack Compose et AndroidX", "Apache-2.0", "Google : interface, WorkManager, cycle de vie.", "https://developer.android.com/jetpack/androidx"),
    Credit("Kotlin et kotlinx.coroutines", "Apache-2.0", "JetBrains.", "https://github.com/JetBrains/kotlin"),
    Credit("Lucide", "ISC", "Icône de la roue dentée.", "https://lucide.dev/license"),
    Credit("Logos des fournisseurs", "Marques déposées", "Fichiers des services publiés sur Wikimedia Commons. Les marques appartiennent à leurs propriétaires et servent seulement à nommer le service.", "https://commons.wikimedia.org"),
)

private val FONTS = listOf(
    Credit("Bricolage Grotesque", "OFL-1.1", "Mathieu Triay et les auteurs de Bricolage Grotesque.", "https://github.com/ateliertriay/bricolage"),
    Credit("Figtree", "OFL-1.1", "Erik Kennedy et les auteurs de Figtree.", "https://github.com/erikdkennedy/figtree"),
    Credit("JetBrains Mono", "OFL-1.1", "JetBrains.", "https://github.com/JetBrains/JetBrainsMono"),
)

/** Auteurs et licences des modèles, bibliothèques et polices utilisés par Dicto. */
@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val models = remember { runCatching { DictoApp.instance.models.credits() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Crédits et licences", onBack)
        Text(
            "Dicto est fait de travaux ouverts. Les modèles se téléchargent depuis leurs dépôts d'origine ; " +
                "touche une ligne pour ouvrir sa source et sa licence.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dicto.InkMuted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp),
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Modèles de reconnaissance vocale")
            CreditList(models)
            SectionTitle("Logiciels")
            CreditList(LIBRARIES)
            SectionTitle("Polices")
            CreditList(FONTS)
        }
    }
}

@Composable
private fun CreditList(items: List<Credit>) {
    val context = LocalContext.current
    DictoCard(padding = PaddingValues(0.dp)) {
        Column {
            items.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Ouvrir la source") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Pill(c.license, Dicto.AccentContainer, Dicto.Accent)
                    }
                    Text(c.credit, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
                }
            }
        }
    }
}
