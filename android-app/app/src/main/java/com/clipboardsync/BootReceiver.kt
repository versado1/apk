package com.clipboardsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(MainActivity.KEY_AUTO_START, false)
            
            if (autoStart) {
                val serverIp = prefs.getString(MainActivity.KEY_SERVER_IP, "") ?: ""
                val serverPort = prefs.getString(MainActivity.KEY_SERVER_PORT, "8765") ?: "8765"
                
                if (serverIp.isNotEmpty()) {
                    val serviceIntent = Intent(context, ClipboardService::class.java).apply {
                        putExtra("server_ip", serverIp)
                        putExtra("server_port", serverPort.toIntOrNull() ?: 8765)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
