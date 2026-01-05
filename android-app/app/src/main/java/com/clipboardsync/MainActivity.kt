package com.clipboardsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clipboardsync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false
    
    companion object {
        const val PREFS_NAME = "ClipboardSyncPrefs"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_AUTO_START = "auto_start"
        const val NOTIFICATION_PERMISSION_CODE = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        setupUI()
        loadSettings()
        requestNotificationPermission()
        updateServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
    
    private fun setupUI() {
        // Botón conectar/desconectar
        binding.btnConnect.setOnClickListener {
            if (isServiceRunning) {
                stopClipboardService()
            } else {
                startClipboardService()
            }
        }
        
        // Botón guardar configuración
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        // Botón copiar texto de prueba
        binding.btnTestCopy.setOnClickListener {
            testCopyToClipboard()
        }
        
        // Botón pegar
        binding.btnTestPaste.setOnClickListener {
            testPasteFromClipboard()
        }
        
        // Checkbox inicio automático
        binding.checkAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
        }
    }
    
    private fun loadSettings() {
        binding.editServerIp.setText(prefs.getString(KEY_SERVER_IP, "192.168.1.100"))
        binding.editServerPort.setText(prefs.getString(KEY_SERVER_PORT, "8765"))
        binding.checkAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, false)
    }
    
    private fun saveSettings() {
        val ip = binding.editServerIp.text.toString().trim()
        val port = binding.editServerPort.text.toString().trim()
        
        if (ip.isEmpty()) {
            binding.editServerIp.error = "Ingresa la IP del servidor"
            return
        }
        
        if (port.isEmpty()) {
            binding.editServerPort.error = "Ingresa el puerto"
            return
        }
        
        prefs.edit().apply {
            putString(KEY_SERVER_IP, ip)
            putString(KEY_SERVER_PORT, port)
            apply()
        }
        
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()
        
        // Si el servicio está corriendo, reiniciarlo con la nueva configuración
        if (isServiceRunning) {
            stopClipboardService()
            startClipboardService()
        }
    }
    
    private fun startClipboardService() {
        val ip = binding.editServerIp.text.toString().trim()
        val port = binding.editServerPort.text.toString().trim()
        
        if (ip.isEmpty() || port.isEmpty()) {
            Toast.makeText(this, "⚠️ Configura la IP y puerto primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Guardar configuración
        saveSettings()
        
        val intent = Intent(this, ClipboardService::class.java).apply {
            putExtra("server_ip", ip)
            putExtra("server_port", port.toIntOrNull() ?: 8765)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isServiceRunning = true
        updateUI()
        Toast.makeText(this, "🚀 Servicio iniciado", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopClipboardService() {
        val intent = Intent(this, ClipboardService::class.java)
        stopService(intent)
        
        isServiceRunning = false
        updateUI()
        Toast.makeText(this, "⏹️ Servicio detenido", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateServiceStatus() {
        isServiceRunning = ClipboardService.isRunning
        updateUI()
    }
    
    private fun updateUI() {
        if (isServiceRunning) {
            binding.btnConnect.text = "⏹️ Desconectar"
            binding.btnConnect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            binding.statusIndicator.setBackgroundResource(R.drawable.status_connected)
            binding.textStatus.text = "Estado: Conectado"
            binding.textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.btnConnect.text = "▶️ Conectar"
            binding.btnConnect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            binding.textStatus.text = "Estado: Desconectado"
            binding.textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }
    
    private fun testCopyToClipboard() {
        val testText = "Prueba desde Android - ${System.currentTimeMillis()}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("test", testText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "📋 Copiado: $testText", Toast.LENGTH_SHORT).show()
    }
    
    private fun testPasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            binding.textClipboardContent.text = "📋 Contenido actual:\n$text"
            binding.textClipboardContent.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "⚠️ Portapapeles vacío", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }
}
