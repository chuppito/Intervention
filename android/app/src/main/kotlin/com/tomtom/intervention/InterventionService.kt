package com.tomtom.intervention

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences

class InterventionService : NotificationListenerService() {

    private val channelId = "intervention_service"

    // onListenerConnected() est garanti par le système dès que l'accès aux
    // notifications est actif — y compris quand Android relance le service
    // après l'avoir tué (Doze, App Standby, gestion batterie du fabricant).
    // C'est ici qu'il faut passer en foreground, pas seulement dans
    // onStartCommand (qui n'était en réalité jamais appelé, car rien ne
    // faisait startService() sur ce service : le service tournait donc en
    // simple écouteur lié, sans les protections d'un foreground service,
    // d'où le besoin de rouvrir l'app de temps en temps).
    override fun onListenerConnected() {
        super.onListenerConnected()
        goForeground()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(android.content.ComponentName(this, InterventionService::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Intervention Active", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Surveillance Intervention active")
            .setContentText("En écoute de Sauv'Life / Staying Alive")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        startForeground(1, notification)
    }

    // Lance Orbe Viewer (app séparée, com.tomtom.orbe) en lui passant le nom
    // de pompier à surveiller, configuré dans les Réglages d'Intervention.
    // Le SYSTEM_ALERT_WINDOW déjà déclaré + le fait que ce service tourne
    // désormais en foreground permettent normalement de démarrer une
    // Activity depuis ce contexte non-Activity malgré les restrictions
    // d'Android 10+ sur les démarrages en arrière-plan. Si Orbe Viewer n'est
    // pas installé ou si le lancement est refusé par le système, on échoue
    // silencieusement pour ne jamais bloquer l'alerte principale.
    private fun launchOrbe() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val watchName = prefs.getString("flutter.orbe_watch_name", "CALABRO") ?: "CALABRO"
            val intent = Intent().apply {
                setClassName("com.tomtom.orbe", "com.tomtom.orbe.MainActivity")
                putExtra("watch_name", watchName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Orbe Viewer probablement pas installé sur cet appareil.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val selectedApps = prefs.getString("flutter.selected_packages", "") ?: ""
        val pkg = sbn.packageName

        if (selectedApps.contains(pkg)) {
            val extras = sbn.notification.extras
            val titre = extras.getString("android.title") ?: ""
            val texte = extras.getString("android.text") ?: ""
            val messageComplet = "$titre $texte"

            // Réveil écran
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Intervention::Alert")
            wakeLock.acquire(5000)

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("notification_msg", messageComplet)
            }
            startActivity(intent)

            if (pkg == "com.systel.mystartplus" || pkg == "bio.aum.opsready.nexsis") {
                try {
                    Thread.sleep(1500) 
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    launchIntent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                } catch (e: Exception) {}

                if (pkg == "bio.aum.opsready.nexsis") {
                    launchOrbe()
                }
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}