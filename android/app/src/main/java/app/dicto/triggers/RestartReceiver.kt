package app.dicto.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Remet la bulle et le geste après un redémarrage du téléphone ou une mise à jour de Dicto
 * (Android arrête alors le service, et il ne revenait qu'en rouvrant Dicto).
 * Le mot d'activation, lui, utilise le micro : Android interdit de le relancer ici.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { BubbleService.refresh(context) }
        }
    }
}
