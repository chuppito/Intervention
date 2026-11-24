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

        enqueueAlert(pkg, messageComplet)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val queue = java.util.ArrayDeque<Pair<String, String>>()
    private var busy = false
    private var player: android.media.MediaPlayer? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ready = false
    private var sequence = 0
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun log(message: String) {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val old = try {
            org.json.JSONArray(prefs.getString("flutter.logs", "[]") ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        android.util.Log.i("Intervention", message)
        // SharedPreferences Flutter utilise un encodage propre aux listes.
        // Journal natif séparé, lu par Flutter.
        val native = try { org.json.JSONArray(prefs.getString("flutter.native_logs", "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val next = org.json.JSONArray()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
        next.put("[$date] $message")
        for (i in 0 until minOf(native.length(), 199)) next.put(native.getString(i))
        prefs.edit().putString("flutter.native_logs", next.toString()).apply()
    }

    private fun enqueueAlert(pkg: String, message: String) {
        handler.post {
            queue.add(Pair(pkg, message))
            if (!busy) nextAlert()
        }
    }

    private fun nextAlert() {
        val item = queue.poll() ?: run { busy = false; return }
        busy = true
        val token = ++sequence
        val (pkg, message) = item
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val lower = message.lowercase(java.util.Locale.ROOT)
        val ignored = try { org.json.JSONArray(prefs.getString("flutter.native_ignore_words", "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val blocked = (0 until ignored.length()).any {
            val word = ignored.getString(it).trim().lowercase(java.util.Locale.ROOT)
            word.isNotEmpty() && lower.contains(word)
        }
        fun launchSource() {
            try {
                if (pkg == "bio.aum.opsready.nexsis") {
                    packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
                    }
                    handler.postDelayed({ launchOrbe() }, 1500)
                } else {
                    packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
                    }
                }
            } catch (e: Exception) { log("Ouverture impossible : ${e.message}") }
        }
        if (blocked) {
            log("Alerte bloquée : $message")
            if (pkg == "bio.aum.opsready.nexsis") launchSource()
            handler.post { nextAlert() }
            return
        }
        val rules = try { org.json.JSONArray(prefs.getString("flutter.rules", "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val sounds = try { org.json.JSONObject(prefs.getString("flutter.app_sounds", "{}")) }
            catch (_: Exception) { org.json.JSONObject() }
        var sound = sounds.optString(pkg, "gare.ogg")
        var speech = message
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val word = rule.optString("if").trim().lowercase(java.util.Locale.ROOT)
            if (word.isNotEmpty() && lower.contains(word)) {
                sound = rule.optString("sound", "gare.ogg")
                speech = rule.optString("say").replace("{notif}", message)
                break
            }
        }
        log("Alerte : $speech ($sound)")
        val manager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try { manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0) } catch (_: Exception) {}
        fun finish() {
            if (token != sequence) return
            sequence++
            player?.release(); player = null
            launchSource()
            handler.post { nextAlert() }
        }
        fun speak() {
            if (token != sequence) return
            if (speech.isBlank()) { finish(); return }
            fun playVoice() {
                if (token != sequence) return
                val engine = tts
                if (!ready || engine == null) { log("Synthèse vocale indisponible"); finish(); return }
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { if (token == sequence) { log("Annonce terminée : $speech"); finish() } } }
                    override fun onError(id: String?) { handler.post { if (token == sequence) { log("Erreur synthèse : $speech"); finish() } } }
                })
                val result = engine.speak(speech, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, token.toString())
                if (result == android.speech.tts.TextToSpeech.ERROR) { log("Erreur lancement voix"); finish() }
                else handler.postDelayed({ if (token == sequence) { engine.stop(); log("Délai vocal dépassé"); finish() } }, 30000)
            }
            if (ready) playVoice() else {
                tts?.shutdown()
                tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
                    handler.post {
                        if (token == sequence) {
                            val engine = tts
                            ready = status == android.speech.tts.TextToSpeech.SUCCESS && engine != null &&
                                engine.setLanguage(java.util.Locale.FRANCE) >= 0
                            engine?.setAudioAttributes(attributes)
                            engine?.setSpeechRate(1.0f)
                            playVoice()
                        }
                    }
                }
                handler.postDelayed({ if (token == sequence && !ready) { log("Initialisation voix expirée"); finish() } }, 10000)
            }
        }
        if (sound == "Aucun") { speak(); return }
        try {
            val media = android.media.MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            if (sound == "gare.ogg" || sound == "bruissement.ogg") {
                assets.openFd("flutter_assets/assets/audio/$sound").use {
                    media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                media.setDataSource(java.io.File(filesDir.parentFile, "app_flutter/sounds/$sound").absolutePath)
            }
            media.setOnCompletionListener { if (token == sequence) { player?.release(); player = null; speak() } }
            media.setOnErrorListener { _, _, _ -> if (token == sequence) { player?.release(); player = null; log("Erreur son : $sound"); speak() }; true }
            media.prepare()
            media.start()
        } catch (e: Exception) { player?.release(); player = null; log("Erreur son : ${e.message}"); speak() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        tts?.shutdown()
        super.onDestroy()
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
