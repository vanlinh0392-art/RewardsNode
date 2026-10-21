package com.agy.rewardsnode.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.Toast
import com.agy.rewardsnode.R
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import com.agy.rewardsnode.utils.TelegramNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình Cài đặt cấu hình: Cooldown, Batch Size, Telegram Bot, Pin và Mạng.
 * 100% Native Android SDK (không phụ thuộc AppCompat).
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: EncryptedPreferences
    private val scope = CoroutineScope(AndroidMainDispatcher)

    private lateinit var etCooldown: EditText
    private lateinit var etBatch: EditText
    private lateinit var etTelegramToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var swWifiOnly: Switch
    private lateinit var swBatteryGuard: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = EncryptedPreferences(this)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener { finish() }
        etCooldown = findViewById(R.id.etCooldownMinutes)
        etBatch = findViewById(R.id.etBatchSize)
        etTelegramToken = findViewById(R.id.etTelegramToken)
        etTelegramChatId = findViewById(R.id.etTelegramChatId)
        swWifiOnly = findViewById(R.id.swWifiOnly)
        swBatteryGuard = findViewById(R.id.swBatteryGuard)
    }

    private fun loadSettings() {
        etCooldown.setText(prefs.cooldownMinutes.toString())
        etBatch.setText(prefs.batchSize.toString())
        etTelegramToken.setText(prefs.telegramBotToken)
        etTelegramChatId.setText(prefs.telegramChatId)
        swWifiOnly.isChecked = prefs.isWifiOnly
        swBatteryGuard.isChecked = prefs.isBatteryGuardEnabled
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val cooldown = etCooldown.text.toString().toIntOrNull() ?: 18
            val batch = etBatch.text.toString().toIntOrNull() ?: 3

            prefs.cooldownMinutes = cooldown
            prefs.batchSize = batch
            prefs.telegramBotToken = etTelegramToken.text.toString().trim()
            prefs.telegramChatId = etTelegramChatId.text.toString().trim()
            prefs.isWifiOnly = swWifiOnly.isChecked
            prefs.isBatteryGuardEnabled = swBatteryGuard.isChecked

            Toast.makeText(this, "Đã lưu cài đặt thành công", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnTestTelegram).setOnClickListener {
            val token = etTelegramToken.text.toString().trim()
            val chatId = etTelegramChatId.text.toString().trim()

            if (token.isBlank() || chatId.isBlank()) {
                Toast.makeText(this, "Vui lòng nhập Token và Chat ID trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scope.launch {
                val notifier = TelegramNotifier(token, chatId)
                val ok = notifier.sendMessage("🔔 *Rewards Node Test*\nKết nối Telegram Bot thành công từ Android!")
                if (ok) {
                    Toast.makeText(this@SettingsActivity, "Gửi tin nhắn thành công!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Gửi thất bại. Kiểm tra lại Token/Chat ID", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnBatteryOptimization).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            } else {
                Toast.makeText(this, "Ứng dụng đã được cấp quyền chạy ngầm không giới hạn!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
