package com.tomtom.intervention

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// La permission RECEIVE_BOOT_COMPLETED était déclarée dans le manifeste mais
// aucun receiver n'y répondait : après un redémarrage du téléphone, le
// service ne redémarrait que via le rebind automatique du système (pas
// toujours immédiat). Ce receiver le relance explicitement en foreground.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InterventionService::class.java)
            )
        }
    }
}
