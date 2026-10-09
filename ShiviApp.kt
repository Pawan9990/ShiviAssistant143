package com.shivi.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ShiviAccessibilityService : AccessibilityService() {
    companion object {
        var instance: ShiviAccessibilityService? = null
            private set
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun clickElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val list = root.findAccessibilityNodeInfosByText(text)
        for (node in list) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                curr = curr.parent
            }
        }
        return false
    }
}

class ShiviVoiceEngine(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onListeningStateChanged(true) }
                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) onSpeechRecognized(matches[0])
                    }
                    override fun onError(error: Int) { onListeningStateChanged(false) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { onListeningStateChanged(false) }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        textToSpeech = TextToSpeech(context, this)
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
    }

    fun speak(text: String) {
        if (isTtsReady) textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SHIVI")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.ENGLISH
            textToSpeech?.setPitch(1.1f)
            isTtsReady = true
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }
}

class ShiviOverlayService : Service() {
    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var voiceEngine: ShiviVoiceEngine? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "shivi_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Shivi Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Shivi Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1001, notif)

        voiceEngine = ShiviVoiceEngine(
            this,
            onSpeechRecognized = { cmd -> handleCommand(cmd) },
            onListeningStateChanged = { listening -> overlayView?.alpha = if (listening) 1.0f else 0.7f }
        )

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val orb = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_online)
        }
        overlayView = orb

        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f; var isClick = true
        orb.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; touchX = ev.rawX; touchY = ev.rawY; isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
                    params.x = initialX + dx; params.y = initialY + dy
                    wm.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) voiceEngine?.startListening()
                    true
                }
                else -> false
            }
        }
        wm.addView(overlayView, params)
    }

    private fun handleCommand(cmd: String) {
        val lower = cmd.lowercase()
        val acc = ShiviAccessibilityService.instance
        when {
            lower.contains("home") -> { acc?.pressHome(); voiceEngine?.speak("Opening home.") }
            lower.contains("back") -> { acc?.pressBack(); voiceEngine?.speak("Navigating back.") }
            lower.contains("notification") -> { acc?.openNotifications(); voiceEngine?.speak("Opening notifications.") }
            else -> voiceEngine?.speak("Executed: $cmd")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine?.destroy()
        overlayView?.let { wm.removeView(it) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
