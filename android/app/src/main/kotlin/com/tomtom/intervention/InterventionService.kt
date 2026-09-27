package com.tomtom.intervention

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class InterventionService : NotificationListenerService() {

    private val channelId = "intervention_service_silent"
    private val flutterChannelName = "com.tomtom.intervention/notify"

    // Notifications déjà traitées.
    private val processedNotifications = mutableSetOf<String>()

    // Moteur Flutter sans interface graphique.
    // Il permet de conserver toute la logique existante de main.dart
    // (règles, sons, TTS, préférences...) sans lancer MainActivity.
    private var flutterEngine: FlutterEngine? = null
    private var flutterChannel: MethodChannel? = null
    private var flutterReady = false

    private val pendingNotifications = ArrayDeque<Pair<String, String>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        goForeground()
        startBackgroundFlutterEngine()
    }

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
    // FLUTTER SANS INTERFACE
    // ---------------------------------------------------------

    private fun startBackgroundFlutterEngine() {
        if (flutterEngine != null) return

        try {
            val engine = FlutterEngine(this)

            val channel = MethodChannel(
                engine.dartExecutor.binaryMessenger,
                flutterChannelName
            )

            channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "flutterReady" -> {
                        flutterReady = true
                        result.success(true)
                        flushPendingNotifications()
                    }

                    else -> result.notImplemented()
                }
            }

            flutterEngine = engine
            flutterChannel = channel

            engine.dartExecutor.executeDartEntrypoint(
                io.flutter.embedding.engine.dart.DartExecutor.DartEntrypoint.createDefault()
            )

        } catch (_: Exception) {
            flutterEngine = null
            flutterChannel = null
            flutterReady = false
        }
    }

    private fun sendToFlutter(
        message: String,
        packageName: String
    ) {
        mainHandler.post {
            if (!flutterReady || flutterChannel == null) {
                pendingNotifications.addLast(
                    Pair(message, packageName)
                )
                return@post
            }

            flutterChannel?.invokeMethod(
                "onNotificationReceived",
                mapOf(
                    "message" to message,
                    "packageName" to packageName
                )
            )
        }
    }

    private fun flushPendingNotifications() {
        if (!flutterReady || flutterChannel == null) return

        while (pendingNotifications.isNotEmpty()) {
            val item = pendingNotifications.removeFirst()

            flutterChannel?.invokeMethod(
                "onNotificationReceived",
                mapOf(
                    "message" to item.first,
                    "packageName" to item.second
                )
            )
        }
    }

    override fun onDestroy() {
        mainHandler.post {
            flutterChannel?.setMethodCallHandler(null)
            flutterChannel = null
            flutterReady = false

            flutterEngine?.destroy()
            flutterEngine = null
            pendingNotifications.clear()
        }

        super.onDestroy()
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

        } catch (_: Exception) {
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

        val selectedApps = selectedAppsString
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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

        if (titre.isEmpty() && texte.isEmpty()) {
            return
        }

        // -----------------------------------------------------
        // DÉDUPLICATION
        // -----------------------------------------------------

        val notificationKey = sbn.key

        if (processedNotifications.contains(notificationKey)) {
            return
        }

        processedNotifications.add(notificationKey)

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
            // IMPORTANT :
            // aucune MainActivity n'est lancée ici.
            // Le traitement se fait dans le moteur Flutter invisible.
            sendToFlutter(messageComplet, pkg)

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

                } catch (_: Exception) {
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
