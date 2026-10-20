package com.agy.rewardsnode.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.databinding.DialogLoginBottomSheetBinding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * BottomSheetDialogFragment cung cấp WebView tương tác để đăng nhập tài khoản Microsoft,
 * giải quyết 2FA (Authenticator, SMS OTP) và Captcha dễ dàng.
 */
class LoginBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogLoginBottomSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var profileManager: ProfileManager
    private lateinit var database: AppDatabase

    private var existingAccountId: String? = null
    private var detectedEmail: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogLoginBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Mở rộng bottom sheet full màn hình để người dùng nhập liệu 2FA thuận tiện
        val dialog = dialog as? BottomSheetDialog
        dialog?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        dialog?.behavior?.peekHeight = resources.displayMetrics.heightPixels
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        profileManager = ProfileManager(context)
        database = AppDatabase.getDatabase(context)

        existingAccountId = arguments?.getString(ARG_ACCOUNT_ID)
        val profileId = existingAccountId ?: UUID.randomUUID().toString()

        setupWebView(profileId)

        binding.btnReloadWebView.setOnClickListener {
            binding.loginWebView.reload()
        }

        binding.btnCancelLogin.setOnClickListener {
            dismiss()
        }

        binding.btnSaveLoginSession.setOnClickListener {
            saveSession(profileId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(profileId: String) {
        val webView = binding.loginWebView
        val cookieManager = profileManager.getCookieManagerForProfile(profileId)
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EdgA/128.0.0.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.pbWebLoading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pbWebLoading.visibility = View.GONE

                // Kiểm tra xem URL có chứa thông tin email hoặc đã về đích đăng nhập thành công
                url?.let { currentUrl ->
                    if (currentUrl.contains("bing.com") || currentUrl.contains("account.microsoft.com")) {
                        // Thử lấy email từ DOM hoặc gợi ý lưu phiên
                        extractUserEmail(view)
                    }
                }
            }
        }

        // Tải trang đăng nhập chính thức của Microsoft
        webView.loadUrl("https://login.live.com/")
    }

    private fun extractUserEmail(webView: WebView?) {
        val js = """
            (function() {
                var el = document.getElementById('displayName') || document.querySelector('.mectrl_name') || document.querySelector('[data-test-id="user-email"]');
                return el ? el.innerText : '';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            val clean = result.replace("\"", "").trim()
            if (clean.isNotBlank() && clean != "null") {
                detectedEmail = clean
            }
        }
    }

    private fun saveSession(profileId: String) {
        val cookieManager = profileManager.getCookieManagerForProfile(profileId)
        val cookies = cookieManager.getCookie("https://login.live.com") ?: ""

        val email = if (detectedEmail.isNotBlank()) detectedEmail else "account_${profileId.take(6)}@outlook.com"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val account = AccountProfile(
                    id = profileId,
                    email = email,
                    profileName = email,
                    cookiesSnapshot = cookies,
                    level = 2,
                    targetSearches = 35
                )
                database.rewardsDao().insertOrUpdateProfile(account)
            }
            Toast.makeText(requireContext(), "Đã lưu phiên tài khoản: $email", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.loginWebView.stopLoading()
        _binding = null
    }

    companion object {
        private const val ARG_ACCOUNT_ID = "account_id"

        fun newInstance(accountId: String? = null): LoginBottomSheet {
            return LoginBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACCOUNT_ID, accountId)
                }
            }
        }
    }
}
