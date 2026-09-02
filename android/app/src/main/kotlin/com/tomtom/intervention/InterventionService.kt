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

class InterventionService : NotificationListenerService() {

    // Nouveau canal pour que Android ne réutilise pas l'ancien canal HIGH.
    private val channelId = "intervention_service_silent"

    // Notifications déjà traitées.
    // Cela évite qu'une même notification soit retraitée à chaque
    // mise à jour de son contenu par l'application source.
    private val processedNotifications = mutableSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        goForeground()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(
            android.content.ComponentName(
                this,
                InterventionService::class.java
            )
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Intervention Active",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(
            this,
            channelId
        )
            .setContentTitle("Surveillance Intervention active")
            .setContentText("En écoute des applications surveillées")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    // ---------------------------------------------------------
    // ORBE VIEWER
    // ---------------------------------------------------------

    private fun launchOrbe() {
        try {
            val prefs = getSharedPreferences(
                "FlutterSharedPreferences",
                Context.MODE_PRIVATE
            )

            val watchName = prefs.getString(
                "flutter.orbe_watch_name",
                "CALABRO"
            ) ?: "CALABRO"

            val intent = Intent().apply {
                setClassName(
                    "com.tomtom.orbe",
                    "com.tomtom.orbe.MainActivity"
                )

                putExtra("watch_name", watchName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(intent)

        } catch (e: Exception) {
            // Orbe Viewer probablement pas installé.
        }
    }

    // ---------------------------------------------------------
    // NOTIFICATION REÇUE
    // ---------------------------------------------------------

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        val prefs = getSharedPreferences(
            "FlutterSharedPreferences",
            Context.MODE_PRIVATE
        )

        val selectedAppsString = prefs.getString(
            "flutter.selected_packages",
            ""
        ) ?: ""

        val pkg = sbn.packageName

        // Liste dynamique des applications sélectionnées dans Intervention.
        val selectedApps = selectedAppsString
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Cette application n'est pas surveillée.
        if (!selectedApps.contains(pkg)) {
            return
        }

        val notification = sbn.notification
        val extras = notification.extras

        val titre = extras
            .getString("android.title")
            ?.trim()
            ?: ""

        val texte = extras
            .getString("android.text")
            ?.trim()
            ?: ""

        // Pas de contenu exploitable.
        if (titre.isEmpty() && texte.isEmpty()) {
            return
        }

        // -----------------------------------------------------
        // DÉDUPLICATION
        // -----------------------------------------------------
        //
        // Une même notification peut être "postée" plusieurs fois
        // lorsqu'une application met simplement à jour son contenu.
        //
        // On utilise la clé Android de la notification.
        //
        // première apparition -> traitée
        // mise à jour          -> ignorée
        // nouvelle notification -> traitée
        //
        // IMPORTANT :
        // On ne filtre PAS isOngoing.
        // Une vraie alerte peut parfaitement être ongoing.
        //

        val notificationKey = sbn.key

        if (processedNotifications.contains(notificationKey)) {
            return
        }

        processedNotifications.add(notificationKey)

        // Évite que la liste grossisse indéfiniment.
        if (processedNotifications.size > 200) {
            val iterator = processedNotifications.iterator()

            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }

        val messageComplet = "$titre $texte".trim()

        // -----------------------------------------------------
        // RÉVEIL DE L'ÉCRAN
        // -----------------------------------------------------

        val pm = getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Intervention::Alert"
        )

        wakeLock.acquire(5000)

        try {

            // -------------------------------------------------
            // ENVOI À INTERVENTION
            // -------------------------------------------------

            val intent = Intent(
                this,
                MainActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    "notification_msg",
                    messageComplet
                )
            }

            startActivity(intent)

            // -------------------------------------------------
            // MYSTART+ / NEXSIS
            // -------------------------------------------------

            if (pkg == "com.systel.mystartplus" ||
                pkg == "bio.aum.opsready.nexsis"
            ) {

                try {

                    Thread.sleep(1500)

                    val launchIntent =
                        packageManager.getLaunchIntentForPackage(pkg)

                    launchIntent?.let {
                        it.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )

                        startActivity(it)
                    }

                } catch (e: Exception) {
                    // Ne jamais bloquer l'alerte principale.
                }

                // NEXSIS → Orbe Viewer
                if (pkg == "bio.aum.opsready.nexsis") {
                    launchOrbe()
                }
            }

        } finally {

            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    // ---------------------------------------------------------
    // NOTIFICATION SUPPRIMÉE
    // ---------------------------------------------------------

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {
        super.onNotificationRemoved(sbn)

        processedNotifications.remove(sbn.key)
    }
}
