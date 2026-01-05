package com.clipboardsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class ClipboardService : Service() {
    
    companion object {
        const val TAG = "ClipboardService"
        const val CHANNEL_ID = "ClipboardSyncChannel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }
    
    private var webSocketClient: WebSocketClient? = null
    private lateinit var clipboardManager: ClipboardManager
    private var lastClipboardContent = ""
    private var serverIp = ""
    private var serverPort = 8765
    private val handler = Handler(Looper.getMainLooper())
    private var clipboardCheckRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var isFromServer = false
    
    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverIp = intent?.getStringExtra("server_ip") ?: "192.168.1.100"
        serverPort = intent?.getIntExtra("server_port", 8765) ?: 8765
        
        startForeground(NOTIFICATION_ID, createNotification("Conectando..."))
        isRunning = true
        
        connectToServer()
        startClipboardMonitoring()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopClipboardMonitoring()
        disconnectFromServer()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sincronización de portapapeles"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📋 Clipboard Sync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun connectToServer() {
        try {
            val uri = URI("ws://$serverIp:$serverPort")
            
            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "Conectado al servidor")
                    handler.post {
                        updateNotification("✅ Conectado a $serverIp")
                    }
                    
                    // Enviar portapapeles actual al conectar
                    getCurrentClipboard()?.let { content ->
                        if (content.isNotEmpty()) {
                            sendToServer(content)
                        }
                    }
                }
                
                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            val json = JSONObject(it)
                            if (json.getString("type") == "clipboard") {
                                val content = json.getString("content")
                                val source = json.optString("source", "unknown")
                                
                                if (content != lastClipboardContent && source != "android") {
                                    Log.d(TAG, "Recibido del servidor: ${content.take(50)}...")
                                    handler.post {
                                        setClipboardFromServer(content)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parseando mensaje: ${e.message}")
                        }
                    }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Desconectado: $reason")
                    handler.post {
                        updateNotification("⚠️ Desconectado - Reconectando...")
                        scheduleReconnect()
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Error WebSocket: ${ex?.message}")
                    handler.post {
                        updateNotification("❌ Error de conexión")
                    }
                }
            }
            
            webSocketClient?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando: ${e.message}")
            updateNotification("❌ Error: ${e.message}")
            scheduleReconnect()
        }
    }
    
    private fun disconnectFromServer() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        webSocketClient?.close()
        webSocketClient = null
    }
    
    private fun scheduleReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (isRunning) {
                Log.d(TAG, "Intentando reconectar...")
                connectToServer()
            }
        }
        handler.postDelayed(reconnectRunnable!!, 5000)
    }
    
    private fun startClipboardMonitoring() {
        // Obtener contenido inicial
        lastClipboardContent = getCurrentClipboard() ?: ""
        
        // Monitorear cambios periódicamente
        clipboardCheckRunnable = object : Runnable {
            override fun run() {
                checkClipboardChanges()
                if (isRunning) {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(clipboardCheckRunnable!!)
    }
    
    private fun stopClipboardMonitoring() {
        clipboardCheckRunnable?.let { handler.removeCallbacks(it) }
    }
    
    private fun checkClipboardChanges() {
        val current = getCurrentClipboard()
        
        if (current != null && current != lastClipboardContent && !isFromServer) {
            lastClipboardContent = current
            Log.d(TAG, "Cambio detectado: ${current.take(50)}...")
            sendToServer(current)
        }
        
        isFromServer = false
    }
    
    private fun getCurrentClipboard(): String? {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo portapapeles: ${e.message}")
            null
        }
    }
    
    private fun setClipboardFromServer(content: String) {
        try {
            isFromServer = true
            lastClipboardContent = content
            
            val clip = ClipData.newPlainText("synced", content)
            clipboardManager.setPrimaryClip(clip)
            
            Log.d(TAG, "Portapapeles actualizado desde servidor")
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo portapapeles: ${e.message}")
        }
    }
    
    private fun sendToServer(content: String) {
        try {
            if (webSocketClient?.isOpen == true) {
                val json = JSONObject().apply {
                    put("type", "clipboard")
                    put("content", content)
                    put("source", "android")
                }
                webSocketClient?.send(json.toString())
                Log.d(TAG, "Enviado al servidor: ${content.take(50)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando: ${e.message}")
        }
    }
}
