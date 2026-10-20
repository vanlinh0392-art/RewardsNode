package com.agy.rewardsnode.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.databinding.ActivitySettingsBinding
import com.agy.rewardsnode.utils.TelegramNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: EncryptedPreferences
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = EncryptedPreferences(this)

        setSupportActionBar(binding.settingsToolbar)
        binding.settingsToolbar.setNavigationOnClickListener {
            finish()
        }

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.etCooldownMinutes.setText(prefs.cooldownMinutes.toString())
        binding.etBatchSize.setText(prefs.batchSize.toString())
        binding.etTelegramToken.setText(prefs.telegramBotToken)
        binding.etTelegramChatId.setText(prefs.telegramChatId)
        binding.swWifiOnly.isChecked = prefs.isWifiOnly
        binding.swBatteryGuard.isChecked = prefs.isBatteryGuardEnabled
    }

    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            val cooldown = binding.etCooldownMinutes.text.toString().toIntOrNull() ?: 18
            val batch = binding.etBatchSize.text.toString().toIntOrNull() ?: 3

            prefs.cooldownMinutes = cooldown
            prefs.batchSize = batch
            prefs.telegramBotToken = binding.etTelegramToken.text.toString().trim()
            prefs.telegramChatId = binding.etTelegramChatId.text.toString().trim()
            prefs.isWifiOnly = binding.swWifiOnly.isChecked
            prefs.isBatteryGuardEnabled = binding.swBatteryGuard.isChecked

            Toast.makeText(this, "Đã lưu cài đặt thành công", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnTestTelegram.setOnClickListener {
            val token = binding.etTelegramToken.text.toString().trim()
            val chatId = binding.etTelegramChatId.text.toString().trim()

            if (token.isBlank() || chatId.isBlank()) {
                Toast.makeText(this, "Vui lòng nhập Token và Chat ID trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            coroutineScope.launch {
                val notifier = TelegramNotifier(token, chatId)
                val ok = notifier.sendMessage("🔔 *Rewards Node Test*\nKết nối Telegram Bot thành công từ Android!")
                if (ok) {
                    Toast.makeText(this@SettingsActivity, "Gửi tin nhắn thành công!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Gửi thất bại. Kiểm tra lại Token/Chat ID", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
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
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Ứng dụng đã được cấp quyền bỏ qua tối ưu pin", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
