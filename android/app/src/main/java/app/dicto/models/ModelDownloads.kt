package app.dicto.models

import app.dicto.i18n.tr
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.data.EngineChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uniffi.dicto_ffi.DownloadListener

/** État d'un téléchargement vu par l'interface. */
data class DownloadState(val running: Boolean, val queued: Boolean, val downloaded: Long, val total: Long, val failed: String?)

object ModelDownloads {
    private const val TAG = "model-download"
    private fun unique(id: String) = "model:$id"

    fun start(context: Context, modelId: String, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_ID to modelId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(TAG)
            .addTag(unique(modelId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(unique(modelId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, modelId: String) {
        DictoApp.instance.models.cancel(modelId)
        WorkManager.getInstance(context).cancelUniqueWork(unique(modelId))
    }

    /** Téléchargements en cours ou récents, par identifiant de modèle. */
    fun observe(context: Context): Flow<Map<String, DownloadState>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.mapNotNull { info ->
                val id = info.tags.firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: return@mapNotNull null
                val p = info.progress
                id to DownloadState(
                    running = info.state == WorkInfo.State.RUNNING,
                    queued = info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED,
                    downloaded = p.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0),
                    total = p.getLong(ModelDownloadWorker.KEY_TOTAL, 0),
                    failed = if (info.state == WorkInfo.State.FAILED) info.outputData.getString(ModelDownloadWorker.KEY_ERROR) else null,
                )
            }.toMap()
        }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val modelId = inputData.getString(KEY_ID).orEmpty()
    private var lastNotified = -1L

    override suspend fun doWork(): Result {
        val app = DictoApp.instance
        val name = app.models.models().firstOrNull { it.id == modelId }?.name ?: modelId
        setForeground(foregroundInfo(name, 0, 0))
        return try {
            app.models.download(
                modelId,
                object : DownloadListener {
                    override fun onProgress(downloaded: ULong, total: ULong) {
                        val d = downloaded.toLong()
                        val t = total.toLong()
                        setProgressAsync(workDataOf(KEY_DOWNLOADED to d, KEY_TOTAL to t))
                        val percent = if (t > 0) d * 100 / t else 0
                        if (percent != lastNotified) {
                            lastNotified = percent
                            setForegroundAsync(foregroundInfo(name, d, t))
                        }
                    }
                },
            )
            // Premier modèle installé : on l'utilise directement.
            if (app.settings.engine.value == null) app.settings.setEngine(EngineChoice.Local(modelId))
            Result.success()
        } catch (e: CancellationException) {
            app.models.cancel(modelId)
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun foregroundInfo(name: String, downloaded: Long, total: Long): ForegroundInfo {
        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, DictoApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("Téléchargement de %1\$s", name))
            .setContentText(if (total > 0) "$percent %" else tr("Préparation…"))
            .setProgress(100, percent, total == 0L)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(modelId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }
}
