package app.dicto.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icônes au trait du canvas (grille 24, trait 2). `Icon` les teinte avec la couleur du contenu. */
object DictoIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 2f, filled: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Mic = icon("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val MicBold = icon("mic-bold", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3", stroke = 2.2f)
    val Settings = icon(
        "settings", "M4 7h10", "M18 7h2", "M16 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
        "M4 17h2", "M10 17h10", "M8 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
    )
    val History = icon("history", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 7v5l3 2")
    val Models = icon("models", "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5", "M12 12v9", "M12 12L4 7.5")
    val Triggers = icon("triggers", "M13 2L4 14h7l-1 8 9-12h-7z")
    val Copy = icon("copy", "M12 9h5a3 3 0 0 1 3 3v5a3 3 0 0 1-3 3h-5a3 3 0 0 1-3-3v-5a3 3 0 0 1 3-3z", "M5 15V7a3 3 0 0 1 3-3h7")
    val Share = icon("share", "M12 15V4", "M8 8l4-4 4 4", "M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    val Trash = icon("trash", "M5 7h14", "M10 11v6", "M14 11v6", "M7 7l1 12a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2l1-12", "M10 7V4h4v3")
    val PushToTalk = icon("ptt", "M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16z", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z")
    val Continuous = icon("continuous", "M7 9a3 3 0 1 0 0 6c3 0 7-6 10-6a3 3 0 1 1 0 6c-3 0-7-6-10-6z")
    val Hold = icon("hold", "M12 4v9", "M8 9l4 4 4-4", "M5 19h14")
    val Back = icon("back", "M15 18l-6-6 6-6", stroke = 2.2f)
    val Keyboard = icon(
        "keyboard", "M5 6h14a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z",
        "M6 10h.01", "M10 10h.01", "M14 10h.01", "M18 10h.01", "M8 14h8",
    )
    val Backspace = icon("backspace", "M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7z", "M12 9l6 6", "M18 9l-6 6")
    val Enter = icon("enter", "M20 5v7a3 3 0 0 1-3 3H5", "M9 11l-4 4 4 4")
    val Check = icon("check", "M5 12l5 5 9-10", stroke = 2.4f)
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18", stroke = 2.2f)
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Download = icon("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Search = icon("search", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M20 20l-4-4")
    val Wave = icon("wave", "M4 12h1", "M8 9v6", "M12 5v14", "M16 8v8", "M20 12h-1")
    val Eye = icon("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z")
    val EyeOff = icon("eye-off", "M3 3l18 18", "M10.6 5.1A10.5 10.5 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.6 3.5", "M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7c1.9 0 3.5-.6 4.9-1.4", "M9.9 9.9a3 3 0 0 0 4.2 4.2")
    /** Roue dentée (tracé Lucide, licence ISC). */
    val Gear = icon(
        "gear",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z",
    )
    val ChevronDown = icon("chevron-down", "M6 9l6 6 6-6")
    val Pencil = icon("pencil", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Stop = icon("stop", "M9 6h6a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z", filled = true)
}
