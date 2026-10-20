package com.agy.rewardsnode.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agy.rewardsnode.R
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.databinding.ActivityMainBinding
import com.agy.rewardsnode.service.RewardsForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var prefs: EncryptedPreferences
    private lateinit var accountAdapter: AccountAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        prefs = EncryptedPreferences(this)

        setupRecyclerView()
        setupListeners()
        observeAccounts()
        checkPermissions()
        updateServiceUIState()
    }

    override fun onResume() {
        super.onResume()
        updateServiceUIState()
    }

    private fun setupRecyclerView() {
        accountAdapter = AccountAdapter(
            onReAuthClicked = { account ->
                LoginBottomSheet.newInstance(account.id).show(supportFragmentManager, "LoginSheet")
            },
            onEditProxyClicked = { account ->
                showProxyDialog(account)
            },
            onDeleteClicked = { account ->
                showDeleteDialog(account)
            }
        )
        binding.rvAccounts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = accountAdapter
        }
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAddAccount.setOnClickListener {
            LoginBottomSheet.newInstance().show(supportFragmentManager, "LoginSheet")
        }

        binding.btnToggleNode.setOnClickListener {
            toggleNodeService()
        }
    }

    private fun observeAccounts() {
        lifecycleScope.launch {
            database.rewardsDao().getAllActiveProfilesFlow().collectLatest { accounts ->
                accountAdapter.submitList(accounts)
                val totalSearches = accounts.sumOf { it.completedSearchesToday }
                val totalPoints = totalSearches * 3
                if (prefs.isNodeServiceRunning) {
                    binding.tvNodeDetails.text = "Đang chạy ${accounts.size} profile | Hôm nay: $totalSearches lượt ($totalPoints điểm)"
                }
            }
        }
    }

    private fun toggleNodeService() {
        val isRunning = prefs.isNodeServiceRunning
        if (!isRunning) {
            val intent = Intent(this, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            prefs.isNodeServiceRunning = true
            Toast.makeText(this, "Đã khởi động Rewards Node ngầm", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, RewardsForegroundService::class.java).apply {
                action = RewardsForegroundService.ACTION_STOP
            }
            startService(intent)
            prefs.isNodeServiceRunning = false
            Toast.makeText(this, "Đã dừng Rewards Node", Toast.LENGTH_SHORT).show()
        }
        updateServiceUIState()
    }

    private fun updateServiceUIState() {
        if (prefs.isNodeServiceRunning) {
            binding.tvNodeStatus.text = "Trạng thái: Đang chạy ngầm (24/7)"
            binding.tvNodeStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnToggleNode.text = getString(R.string.btn_stop_node)
            binding.btnToggleNode.setBackgroundColor(ContextCompat.getColor(this, R.color.warning))
        } else {
            binding.tvNodeStatus.text = getString(R.string.status_idle)
            binding.tvNodeStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleNode.text = getString(R.string.btn_start_node)
            binding.btnToggleNode.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun showProxyDialog(account: AccountProfile) {
        val input = EditText(this).apply {
            hint = "e.g. socks5://192.168.1.100:1080"
            setText(account.proxyUrl ?: "")
        }

        AlertDialog.Builder(this)
            .setTitle("Cấu hình Proxy cho ${account.email}")
            .setMessage("Nhập proxy dạng socks5://ip:port hoặc http://ip:port (để trống nếu dùng mạng trực tiếp):")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val proxy = input.text.toString().trim().ifBlank { null }
                account.proxyUrl = proxy
                lifecycleScope.launch(Dispatchers.IO) {
                    database.rewardsDao().updateProfile(account)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showDeleteDialog(account: AccountProfile) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận xóa tài khoản")
            .setMessage("Bạn có chắc chắn muốn xóa profile ${account.email} khỏi ứng dụng?")
            .setPositiveButton("Xóa") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.rewardsDao().deleteProfile(account)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
