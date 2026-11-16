package app.dicto.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de diagnostic, gardé sur le téléphone : les derniers événements (clavier, bulle)
 * et le dernier plantage. Rien n'est envoyé ; Réglages permet de le copier pour le partager.
 */
object Journal {
    private const val MAX_LINES = 200
    private lateinit var events: File
    private lateinit var crash: File
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun init(context: Context) {
        events = File(context.filesDir, "journal.txt")
        crash = File(context.filesDir, "dernier-plantage.txt")
        synchronized(lines) {
            runCatching { events.readLines().takeLast(MAX_LINES).forEach(lines::addLast) }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crash.writeText("${Date()} · fil ${thread.name} · Android ${Build.VERSION.RELEASE}\n${Log.getStackTraceString(e)}")
                add("plantage", "${e.javaClass.simpleName}: ${e.message}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun add(tag: String, message: String) {
        Log.i("DictoJournal", "$tag: $message")
        if (!::events.isInitialized) return
        val line = "${time.format(Date())} $tag: $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            runCatching { events.writeText(lines.joinToString("\n", postfix = "\n")) }
        }
    }

    fun hasCrash(): Boolean = ::crash.isInitialized && crash.exists()

    /** Le dernier plantage puis les derniers événements, prêts à copier. */
    fun export(): String = buildString {
        if (hasCrash()) append("== Dernier plantage ==\n").append(runCatching { crash.readText() }.getOrDefault("")).append("\n\n")
        append("== Derniers événements ==\n")
        synchronized(lines) { lines.forEach { append(it).append('\n') } }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { events.delete() }
        runCatching { crash.delete() }
    }
}
