package app.dicto.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.dicto.DictoApp
import app.dicto.data.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un message vocal du dossier de WhatsApp. */
data class VoiceNote(val docId: String, val uri: Uri, val name: String, val modifiedAt: Long, val size: Long)

/**
 * Vocaux de WhatsApp, lus dans le dossier « WhatsApp Voice Notes » que l'utilisateur accorde une
 * fois (Android/media/com.whatsapp/…, des sous-dossiers par semaine). Garde aussi la liste des
 * vocaux déjà transcrits, pour les réafficher sans les retranscrire.
 */
object VoiceNotes {
    const val WHATSAPP = "com.whatsapp"
    /**
     * Apps dont les notifications de vocaux déclenchent la transcription automatique. Pas
     * WhatsApp Business : ses vocaux sont dans un autre dossier que celui accordé.
     */
    val APPS = setOf(WHATSAPP)
    private const val INITIAL_DOC = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
    private const val INDEX_MAX = 300
    /** Sous-dossiers (semaines) parcourus, les plus récents. */
    private const val WEEKS = 3

    /** Dossier proposé à l'ouverture du sélecteur. */
    val initialUri: Uri get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", INITIAL_DOC)

    private val settings get() = DictoApp.instance.settings.voiceNotesFolder

    fun folder(): Uri? = settings.value.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** Nom lisible du dossier accordé (« WhatsApp Voice Notes »). */
    fun folderName(): String? = folder()?.let { DocumentsContract.getTreeDocumentId(it).substringAfterLast('/').substringAfterLast(':') }

    fun isGranted(context: Context): Boolean {
        val folder = folder() ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission }
    }

    /** Garde l'accès au dossier choisi dans le sélecteur, au-delà du redémarrage. */
    fun grant(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folder()?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        settings.set(tree.toString())
        Journal.add("vocaux", "dossier accordé : ${folderName()}")
    }

    fun forget(context: Context) {
        folder()?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        settings.set("")
    }

    /** Les vocaux les plus récents d'abord ; liste vide sans accès ou en cas d'erreur. */
    suspend fun recent(context: Context, limit: Int = 20): List<VoiceNote> = withContext(Dispatchers.IO) {
        runCatching { list(context, limit) }
            .onFailure { Journal.add("vocaux", "lecture du dossier impossible : ${it.message}") }
            .getOrDefault(emptyList())
    }

    suspend fun latest(context: Context): VoiceNote? = recent(context, 1).firstOrNull()

    private fun list(context: Context, limit: Int): List<VoiceNote> {
        val tree = folder() ?: return emptyList()
        val root = children(context, tree, DocumentsContract.getTreeDocumentId(tree))
        val files = root.filterNot { it.isDir }.toMutableList()
        for (week in newestFolders(root.filter { it.isDir }, WEEKS)) files += children(context, tree, week.docId).filterNot { it.isDir }
        return files
            .filter { isVoiceFile(it.name) }
            .sortedByDescending { it.modified }
            .take(limit)
            .map { VoiceNote(it.docId, DocumentsContract.buildDocumentUriUsingTree(tree, it.docId), it.name, it.modified, it.size) }
    }

    private fun children(context: Context, tree: Uri, parent: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DocEntry(
                            docId = c.getString(0),
                            name = c.getString(1).orEmpty(),
                            isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                            modified = if (c.isNull(3)) 0L else c.getLong(3),
                            size = if (c.isNull(4)) 0L else c.getLong(4),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    // --- Vocaux déjà transcrits ------------------------------------------------------

    private val lock = Any()
    private val index = LinkedHashMap<String, String>()
    private val senderIndex = LinkedHashMap<String, String>()
    private var loaded = false
    private val _texts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val textsFlow = _texts.asStateFlow()
    private val _senders = MutableStateFlow<Map<String, String>>(emptyMap())
    private val sendersFlow = _senders.asStateFlow()

    /** Texte de chaque vocal déjà transcrit, par docId. */
    val texts: StateFlow<Map<String, String>>
        get() {
            ensureLoaded()
            return textsFlow
        }

    /** Expéditeur de chaque vocal (« Marie · Famille »), noté depuis la notification de WhatsApp. */
    val senders: StateFlow<Map<String, String>>
        get() {
            ensureLoaded()
            return sendersFlow
        }

    fun transcript(docId: String): String? {
        ensureLoaded()
        return synchronized(lock) { index[docId] }
    }

    fun sender(docId: String): String? {
        ensureLoaded()
        return synchronized(lock) { senderIndex[docId] }
    }

    fun remember(docId: String, text: String) = put(index, _texts, docId, text)

    fun rememberSender(docId: String, sender: String) = put(senderIndex, _senders, docId, sender)

    private fun put(map: LinkedHashMap<String, String>, flow: MutableStateFlow<Map<String, String>>, docId: String, value: String) {
        ensureLoaded()
        synchronized(lock) {
            map.remove(docId)
            map[docId] = value
            while (map.size > INDEX_MAX) map.remove(map.keys.first())
            flow.value = map.toMap()
            save()
        }
    }

    private fun file() = File(DictoApp.instance.filesDir, "voicenotes.json")

    private fun ensureLoaded() = synchronized(lock) {
        if (loaded) return@synchronized
        loaded = true
        runCatching {
            val raw = file().readText()
            // Première version : un tableau des seuls textes.
            val root = if (raw.trimStart().startsWith("[")) JSONObject().put("texts", JSONArray(raw)) else JSONObject(raw)
            read(root.optJSONArray("texts"), "text", index)
            read(root.optJSONArray("senders"), "sender", senderIndex)
        }
        _texts.value = index.toMap()
        _senders.value = senderIndex.toMap()
    }

    private fun read(array: JSONArray?, key: String, into: LinkedHashMap<String, String>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            into[o.getString("id")] = o.getString(key)
        }
    }

    private fun save() {
        fun write(map: Map<String, String>, key: String) =
            JSONArray().apply { for ((id, v) in map) put(JSONObject().put("id", id).put(key, v)) }
        val root = JSONObject().put("texts", write(index, "text")).put("senders", write(senderIndex, "sender"))
        runCatching { file().writeText(root.toString()) }
    }

    // --- Vocaux rattachés à une notification ------------------------------------------

    private val claimed = mutableSetOf<String>()

    /** Rattache un vocal à une notification de WhatsApp ; faux s'il l'est déjà. */
    fun claim(docId: String): Boolean = synchronized(claimed) { claimed.add(docId) }
}

/** Fichier ou dossier vu dans le dossier accordé. */
internal data class DocEntry(val docId: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

/** Un message vocal de WhatsApp (Opus), ou un autre enregistrement audio du dossier. */
internal fun isVoiceFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return !name.startsWith(".") && ext in setOf("opus", "ogg", "m4a", "aac", "mp3", "amr")
}

/** Les sous-dossiers (semaines « 202647 ») les plus récents. */
internal fun newestFolders(folders: List<DocEntry>, count: Int): List<DocEntry> =
    folders.filterNot { it.name.startsWith(".") }
        .sortedWith(compareByDescending<DocEntry> { it.modified }.thenByDescending { it.name })
        .take(count)

/** WhatsApp annonce un vocal par « 🎤 Message vocal (0:12) », quelle que soit la langue. */
internal fun isVoiceMessage(text: CharSequence?): Boolean = text?.contains("🎤") == true

/**
 * « Marie · Famille » : la personne, puis le groupe s'il y en a un. Le titre d'une notification
 * de groupe peut finir par « (3 messages) » : on l'enlève.
 */
internal fun senderLabel(person: String?, group: String?): String? {
    val clean = { s: String? -> s?.replace(Regex("""\s*\(\d+[^)]*\)\s*$"""), "")?.trim()?.takeIf { it.isNotEmpty() } }
    val p = clean(person)
    val g = clean(group)?.takeIf { it != p }
    return listOfNotNull(p, g).joinToString(" · ").ifEmpty { null }
}
