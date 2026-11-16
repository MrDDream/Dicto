package app.dicto

import app.dicto.i18n.tr
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.dicto.data.Secrets
import app.dicto.data.Settings
import app.dicto.dictation.DictationController
import uniffi.dicto_ffi.ModelManager
import uniffi.dicto_ffi.Storage
import java.io.File

class DictoApp : Application(), androidx.work.Configuration.Provider {
    lateinit var storage: Storage
        private set
    lateinit var models: ModelManager
        private set
    lateinit var settings: Settings
        private set
    lateinit var secrets: Secrets
        private set
    lateinit var dictation: DictationController
        private set

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        app.dicto.data.Journal.init(this)
        app.dicto.update.UpdateChecker.init(this)
        createChannels()
        storage = Storage.open(File(filesDir, "dicto.db").path)
        models = ModelManager(File(filesDir, "models").apply { mkdirs() }.path, "android")
        settings = Settings(storage)
        app.dicto.i18n.I18n.setMode(settings.appLanguage.value)
        app.dicto.ui.theme.ThemeMode.set(settings.theme.value)
        secrets = Secrets(this)
        dictation = DictationController(this).also { it.init() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DICTATION, tr("Dictée en cours"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Affichée tant que le micro est ouvert.")
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, tr("Téléchargement des modèles"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_DICTATION = "dictation"
        const val CHANNEL_DOWNLOADS = "downloads"

        lateinit var instance: DictoApp
            private set
    }
}
