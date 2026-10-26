package com.agy.rewardsnode.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import com.agy.rewardsnode.core.RewardsBalanceExtractor
import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONObject

/**
 * Dialog xem chi tiết rổ điểm thực tế khi bấm vào tên tài khoản.
 * - Thiết kế 100% Native Android SDK phong cách Fluent Dark BottomSheet (AMOLED `#0A0F1D`).
 * - Thẻ tiến độ từng rổ điểm (Desktop 0/90, Mobile 60/60, Edge 0/0, Daily Set 3/3, Offers 85, Lifetime 1694).
 * - Cây Key-Value / JSON Sanitized có thể cuộn, tìm kiếm trực quan bằng EditText và nút "Sao chép JSON" (ClipboardManager).
 */
class AccountBreakdownDialog(
    context: Context,
    private val account: AccountProfile
) : Dialog(context) {

    private lateinit var etSearch: EditText
    private lateinit var tvResultCount: TextView
    private lateinit var llKeyValueContainer: LinearLayout
    private val allKeyValuePairs = mutableListOf<Pair<String, String>>()
    private var sanitizedJsonString: String = "{}"

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val params = win.attributes
            params.gravity = Gravity.BOTTOM
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = (context.resources.displayMetrics.heightPixels * 0.88).toInt()
            win.attributes = params
            win.setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        val telemetry = RewardsBalanceExtractor.latestTelemetryMap[account.id]
            ?: RewardsBalanceExtractor.latestTelemetryMap[account.email]

        // 1. Tính toán giá trị từng rổ điểm thực tế
        val effPcMax = if (account.pcMax > 0) account.pcMax else (telemetry?.pcMax ?: if (account.level == 1) 30 else 90)
        val pcProg = minOf(account.pcProgress, effPcMax)

        val effMobMax = if (account.mobileMax > 0) account.mobileMax else (telemetry?.mobileMax ?: if (account.level == 1) 0 else 60)
        val mobProg = if (effMobMax > 0) minOf(account.mobileProgress, effMobMax) else 0

        val effEdgeMax = if (account.edgeMax > 0) account.edgeMax else (telemetry?.edgeMax ?: 0)
        val edgeProg = if (effEdgeMax > 0) minOf(account.edgeProgress, effEdgeMax) else 0

        val dsTotal = if (account.dailySetTotal > 0) account.dailySetTotal else (telemetry?.dailySetTotal ?: 3)
        val dsDone = account.dailySetDone || (telemetry?.dailySetDone == true) || (account.dailySetProgress >= dsTotal && dsTotal > 0)
        val dsProg = if (dsDone) dsTotal else maxOf(account.dailySetProgress, telemetry?.dailySetProgress ?: 0)

        // Tính offers: lấy từ telemetry.pointsBreakdownJson nếu có, hoặc offersPoints, hoặc phần dư hôm nay
        var offersPts = 0
        val rawBreakdownStr = telemetry?.pointsBreakdownJson
        if (!rawBreakdownStr.isNullOrBlank()) {
            try {
                val bJson = JSONObject(rawBreakdownStr)
                offersPts = bJson.optJSONObject("offers")?.optInt("progress", 0) ?: 0
            } catch (_: Exception) {}
        }
        if (offersPts <= 0) {
            val totalEarned = when {
                (telemetry?.pointsEarnedToday ?: 0) > 0 -> telemetry!!.pointsEarnedToday
                account.pointsEarnedToday > 0 -> account.pointsEarnedToday
                else -> 0
            }
            val searchSum = pcProg + mobProg + edgeProg
            if (totalEarned > searchSum) {
                offersPts = totalEarned - searchSum
            }
        }

        val lifetime = if (account.lifetimePoints > 0) account.lifetimePoints else (telemetry?.lifetimePoints ?: account.availablePoints)
        val availablePts = if (account.availablePoints > 0) account.availablePoints else account.totalPoints

        // 2. Xây dựng JSON Sanitized (Loại bỏ token, cookie, credentials)
        val sanitizedJson = JSONObject().apply {
            put("accountId", account.id)
            put("email", account.email)
            put("profileName", account.profileName)
            put("level", account.level)
            put("availablePoints", availablePts)
            put("totalPoints", account.totalPoints)
            put("lifetimePoints", lifetime)
            put("streakCount", account.streakCount)
            put("pointsEarnedToday", account.pointsEarnedToday)
            put("isEnabled", account.isEnabled)
            put("cookieExpired", account.cookieExpired)
            put("proxyUrl", account.proxyUrl ?: "Direct IP")
            put("lastActiveDate", account.lastActiveDate)

            put("baskets", JSONObject().apply {
                put("desktopSearch", "$pcProg/$effPcMax")
                put("mobileSearch", "$mobProg/$effMobMax")
                put("edgeBonus", "$edgeProg/$effEdgeMax")
                put("dailySet", "$dsProg/$dsTotal")
                put("dailySetDone", dsDone)
                put("offersPoints", offersPts)
                put("readNews", "${account.readProgress}/${if (account.readMax > 0) account.readMax else (telemetry?.readNewsMax ?: 30)}")
            })

            if (!rawBreakdownStr.isNullOrBlank()) {
                try {
                    put("serverBreakdown", JSONObject(rawBreakdownStr))
                } catch (_: Exception) {}
            }

            if (telemetry != null) {
                put("telemetry", JSONObject().apply {
                    put("source", telemetry.source)
                    put("isSuccess", telemetry.isSuccess)
                    put("isAccountSuspended", telemetry.isAccountSuspended)
                    put("streakProtectionOn", telemetry.streakProtectionOn)
                    put("claimablePoints", telemetry.claimablePoints)
                    put("monthlyLevelProgress", telemetry.monthlyLevelProgress)
                    put("monthlyLevelMax", telemetry.monthlyLevelMax)
                    put("hasExactCounters", telemetry.hasExactCounters)
                })
            }
        }
        sanitizedJsonString = sanitizedJson.toString(2)

        // 3. Chuẩn bị danh sách Key-Value phẳng cho bộ lọc tìm kiếm
        allKeyValuePairs.clear()
        allKeyValuePairs.add("account.email" to account.email)
        allKeyValuePairs.add("account.level" to "Level ${account.level}")
        allKeyValuePairs.add("account.availablePoints" to String.format("%,d pts", availablePts))
        allKeyValuePairs.add("account.lifetimePoints" to String.format("%,d pts", lifetime))
        allKeyValuePairs.add("account.streakCount" to "${account.streakCount} ngày")
        allKeyValuePairs.add("account.pointsEarnedToday" to "+${account.pointsEarnedToday} pts")
        allKeyValuePairs.add("basket.desktop" to "$pcProg / $effPcMax ${if (pcProg >= effPcMax && effPcMax > 0) "✓" else ""}")
        allKeyValuePairs.add("basket.mobile" to "$mobProg / $effMobMax ${if (mobProg >= effMobMax && effMobMax > 0) "✓" else ""}")
        allKeyValuePairs.add("basket.edge" to "$edgeProg / $effEdgeMax ${if (edgeProg >= effEdgeMax && effEdgeMax > 0) "✓" else ""}")
        allKeyValuePairs.add("basket.dailySet" to "$dsProg / $dsTotal ${if (dsDone) "✓ (Đã xong)" else ""}")
        allKeyValuePairs.add("basket.offers" to "$offersPts pts")
        allKeyValuePairs.add("basket.readNews" to "${account.readProgress} / ${if (account.readMax > 0) account.readMax else (telemetry?.readNewsMax ?: 30)}")
        allKeyValuePairs.add("account.proxy" to (account.proxyUrl ?: "Direct IP (Không dùng Proxy)"))
        allKeyValuePairs.add("account.isEnabled" to account.isEnabled.toString())
        allKeyValuePairs.add("account.cookieExpired" to account.cookieExpired.toString())
        allKeyValuePairs.add("account.lastActiveDate" to account.lastActiveDate)
        if (telemetry != null) {
            allKeyValuePairs.add("telemetry.source" to telemetry.source)
            allKeyValuePairs.add("telemetry.isAccountSuspended" to telemetry.isAccountSuspended.toString())
            allKeyValuePairs.add("telemetry.streakProtectionOn" to telemetry.streakProtectionOn.toString())
            allKeyValuePairs.add("telemetry.claimablePoints" to "${telemetry.claimablePoints} pts")
            if (telemetry.monthlyLevelMax > 0) {
                allKeyValuePairs.add("telemetry.monthlyLevel" to "${telemetry.monthlyLevelProgress}/${telemetry.monthlyLevelMax}")
            }
        }

        // 4. Xây dựng Layout Root Fluent Dark BottomSheet
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0F1D"))
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            setPadding(dp(16), dp(10), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Drag handle indicator
        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#475569"))
                cornerRadius = dp(2).toFloat()
            }
        }
        rootLayout.addView(dragHandle)

        // Header Title Row (Email + Close button)
        val headerRow = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val rlp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = rlp
        }

        val tvTitle = TextView(context).apply {
            text = account.email
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        titleContainer.addView(tvTitle)

        val tvSubtitle = TextView(context).apply {
            text = "Level ${account.level} • Streak ${account.streakCount} ngày • ${String.format("%,d pts", availablePts)}"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        titleContainer.addView(tvSubtitle)
        headerRow.addView(titleContainer)

        val btnClose = Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
            }
            val rlp = RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = rlp
            setOnClickListener { dismiss() }
        }
        headerRow.addView(btnClose)
        rootLayout.addView(headerRow)

        // 5. Thẻ tiến độ từng rổ điểm (Desktop, Mobile, Edge, Daily Set, Offers, Lifetime)
        val tvSectionBaskets = TextView(context).apply {
            text = "TIẾN ĐỘ RỔ ĐIỂM CHI TIẾT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(4), 0, dp(6))
        }
        rootLayout.addView(tvSectionBaskets)

        // Basket Cards Grid (2 hàng x 3 cột)
        val basketGridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131D31"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        // Hàng 1: Desktop, Mobile, Edge
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        row1.addView(createBasketItemView("🖥️ Desktop", "$pcProg/$effPcMax", pcProg >= effPcMax && effPcMax > 0, Color.parseColor("#10B981")))
        row1.addView(createBasketItemView("📱 Mobile", "$mobProg/$effMobMax", mobProg >= effMobMax && effMobMax > 0, Color.parseColor("#06B6D4")))
        row1.addView(createBasketItemView("🌐 Edge", "$edgeProg/$effEdgeMax", edgeProg >= effEdgeMax && effEdgeMax > 0, Color.parseColor("#F59E0B")))
        basketGridContainer.addView(row1)

        // Hàng 2: Daily Set, Offers, Lifetime
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row2.addView(createBasketItemView("📅 Daily Set", "$dsProg/$dsTotal", dsDone, Color.parseColor("#818CF8")))
        row2.addView(createBasketItemView("🎁 Offers", "$offersPts", offersPts > 0, Color.parseColor("#EC4899")))
        row2.addView(createBasketItemView("🏆 Lifetime", String.format("%,d", lifetime), true, Color.parseColor("#FBBF24")))
        basketGridContainer.addView(row2)

        rootLayout.addView(basketGridContainer)

        // 6. Section Key-Value / JSON Sanitized + Search Bar + Copy Button
        val searchActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        etSearch = EditText(context).apply {
            hint = "🔍 Tìm thuộc tính hoặc giá trị..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = dp(8)
            }
        }
        searchActionRow.addView(etSearch)

        val btnCopyJson = Button(context).apply {
            text = "📋 Sao chép JSON"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Sanitized Rewards Breakdown", sanitizedJsonString)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "✅ Đã sao chép JSON sanitized vào Clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        searchActionRow.addView(btnCopyJson)
        rootLayout.addView(searchActionRow)

        // Result Count Label
        tvResultCount = TextView(context).apply {
            text = "Hiển thị: ${allKeyValuePairs.size}/${allKeyValuePairs.size} thuộc tính"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(6))
        }
        rootLayout.addView(tvResultCount)

        // ScrollView chứa cây Key-Value
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1324"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            isVerticalScrollBarEnabled = true
        }

        llKeyValueContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(llKeyValueContainer)
        rootLayout.addView(scrollView)

        // Đổ dữ liệu Key-Value ban đầu
        populateKeyValueRows(allKeyValuePairs)

        // Lắng nghe tìm kiếm theo thời gian thực
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                filterKeyValueRows(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setContentView(rootLayout)
    }

    private fun createBasketItemView(
        title: String,
        value: String,
        isCompleted: Boolean,
        accentColor: Int
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = dp(4)
                marginStart = dp(4)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A243B"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))

            val tvTitle = TextView(context).apply {
                text = title
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                isSingleLine = true
            }
            addView(tvTitle)

            val tvVal = TextView(context).apply {
                text = if (isCompleted) "$value ✓" else value
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isCompleted) accentColor else Color.parseColor("#F8FAFC"))
                isSingleLine = true
            }
            addView(tvVal)
        }
    }

    private fun populateKeyValueRows(items: List<Pair<String, String>>) {
        llKeyValueContainer.removeAllViews()
        if (items.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Không tìm thấy thuộc tính phù hợp"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            llKeyValueContainer.addView(emptyTv)
            return
        }

        items.forEachIndexed { index, (key, value) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvKey = TextView(context).apply {
                text = key
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#38BDF8"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f)
            }
            row.addView(tvKey)

            val tvVal = TextView(context).apply {
                text = value
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#F1F5F9"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.52f)
            }
            row.addView(tvVal)

            llKeyValueContainer.addView(row)

            if (index < items.size - 1) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(Color.parseColor("#1E293B"))
                }
                llKeyValueContainer.addView(divider)
            }
        }
    }

    private fun filterKeyValueRows(query: String) {
        val filtered = if (query.isBlank()) {
            allKeyValuePairs
        } else {
            allKeyValuePairs.filter {
                it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true)
            }
        }
        tvResultCount.text = "Hiển thị: ${filtered.size}/${allKeyValuePairs.size} thuộc tính"
        populateKeyValueRows(filtered)
    }
}
