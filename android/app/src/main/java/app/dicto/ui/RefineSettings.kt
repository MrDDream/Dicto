package app.dicto.ui

import app.dicto.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.ui.providers.RefineProviders
import app.dicto.ui.theme.Dicto
import uniffi.dicto_ffi.refinePresets
import uniffi.dicto_ffi.translationTargets

/** Retouche par IA : fournisseurs (même rendu que Nautile), consigne, retouche automatique. */
@Composable
fun RefineSettingsCard() {
    val s = DictoApp.instance.settings
    val provider by s.refineProvider.collectAsStateWithLifecycle()
    val preset by s.refinePreset.flow.collectAsStateWithLifecycle()
    val custom by s.refineCustom.flow.collectAsStateWithLifecycle()
    val auto by s.autoRefine.flow.collectAsStateWithLifecycle()
    fun saved() = DictoApp.instance.dictation.applyOptions()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RefineProviders()
        Text(
            tr("Le fournisseur activé reçoit le texte transcrit (jamais l'audio) ; Ollama peut tourner sur ton propre PC. La roue dentée règle la clé, l'adresse et le modèle. Les clés restent chiffrées sur l'appareil (Android Keystore)."),
            style = MaterialTheme.typography.labelSmall,
            color = Dicto.InkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (provider != null) {
            DictoCard {
                Text(tr("Consigne"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pr in refinePresets()) {
                        ChipButton(onClick = { s.refinePreset.set(pr.id); saved() }, accent = preset == pr.id) {
                            Text(tr(pr.name), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    ChipButton(onClick = { s.refinePreset.set("custom"); saved() }, accent = preset == "custom") {
                        Text(tr("Personnalisée"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (preset == "custom") {
                    OutlinedTextField(
                        value = custom, onValueChange = { s.refineCustom.set(it); saved() },
                        label = { Text(tr("Ta consigne")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                SettingRow(tr("Retoucher chaque dictée"), tr("Sinon, utilise « Reformuler » dans l'écran Dictée")) {
                    DictoSwitch(auto, { s.autoRefine.set(it); saved() }, tr("Retoucher chaque dictée"))
                }
            }
            TranslationCard()
        } else {
            Text(
                tr("Active un fournisseur pour retoucher ou traduire tes dictées."),
                style = MaterialTheme.typography.labelSmall,
                color = Dicto.InkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Nom d'une langue de traduction, dans la langue de l'interface. */
fun translationLanguageName(code: String): String = tr(
    when (code) {
        "en" -> "Anglais"
        "fr" -> "Français"
        "es" -> "Espagnol"
        "de" -> "Allemand"
        "it" -> "Italien"
        "pt" -> "Portugais"
        "nl" -> "Néerlandais"
        else -> code
    },
)

/** Traduction à la volée : chaque dictée est écrite directement dans la langue choisie. */
@Composable
private fun TranslationCard() {
    val s = DictoApp.instance.settings
    val target by s.translateTo.flow.collectAsStateWithLifecycle()
    fun choose(code: String) {
        s.translateTo.set(code)
        if (code != "none") s.lastTranslateTo.set(code)
        DictoApp.instance.dictation.applyOptions()
    }
    DictoCard {
        ChoiceRow(
            tr("Traduire en"),
            tr("Tu parles dans ta langue, Dicto écrit dans celle-ci. Passe avant la retouche automatique."),
            listOf("none" to tr("Non")) + translationTargets().map { it to translationLanguageName(it) },
            target,
            ::choose,
        )
    }
}
