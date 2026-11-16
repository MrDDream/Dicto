package app.dicto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dicto.R
import app.dicto.ui.theme.Dicto

/** Le logo : micro sur carré ambre arrondi. */
@Composable
fun LogoMark(size: Dp = 34.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.35f)).background(Dicto.Brand),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size * 1.25f))
    }
}

/** En-tête d'écran secondaire : retour + titre. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 10.dp else 20.dp, end = 12.dp, top = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(app.dicto.ui.theme.DictoIcons.Back, contentDescription = "Retour", tint = Dicto.Ink)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun DictoCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = Dicto.Ink.copy(alpha = 0.12f), spotColor = Dicto.Ink.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(22.dp))
            .background(Dicto.Surface)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted)
        }
    }
}

/** Pastille compacte (« Je t'écoute · 00:07 », « En service »…). */
@Composable
fun Pill(text: String, container: Color, content: Color, dot: Color? = null) {
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(container).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content)
    }
}

/** Bouton « puce » à bord fin, comme le sélecteur de modèle de l'écran Dictée. */
@Composable
fun ChipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (accent) Dicto.AccentContainer else Dicto.Surface,
        contentColor = if (accent) Dicto.Accent else Dicto.Ink,
        border = if (accent) null else androidx.compose.foundation.BorderStroke(1.dp, Dicto.Border),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) Dicto.Accent else Dicto.Border,
        contentColor = Dicto.OnAccent,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (danger) Dicto.RecordingContainer else Dicto.AccentContainer,
        contentColor = if (danger) Dicto.OnRecordingContainer else Dicto.Accent,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Bouton texte discret avec icône (Copier, Partager…). */
@Composable
fun GhostButton(text: String?, icon: ImageVector, onClick: () -> Unit, contentDescription: String? = text, tint: Color = Dicto.Ink) {
    if (text == null) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = tint)) {
            Icon(icon, contentDescription, Modifier.size(18.dp))
        }
        return
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.Transparent, contentColor = tint) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun DictoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.semanticsLabel(label),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Dicto.Surface,
            checkedTrackColor = Dicto.Accent,
            checkedBorderColor = Dicto.Accent,
            uncheckedThumbColor = Dicto.Surface,
            uncheckedTrackColor = Dicto.Border,
            uncheckedBorderColor = Dicto.Border,
        ),
    )
}

/** Ligne de réglage : titre, description, et un contrôle à droite. */
@Composable
fun SettingRow(title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted, fontWeight = FontWeight.Normal)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Les 3 à 4 points « Rapidité ●●●○ ». */
@Composable
fun Dots(value: Int, max: Int = 4, color: Color = Dicto.Accent) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(max) { i ->
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (i < value) color else Color.Transparent)
                    .border(1.dp, if (i < value) color else Dicto.Border, CircleShape),
            )
        }
    }
}

private fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }
