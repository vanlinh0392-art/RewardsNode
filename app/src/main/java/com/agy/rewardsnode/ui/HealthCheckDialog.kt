package com.agy.rewardsnode.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.agy.rewardsnode.core.HealthCheckEngine
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Dialog hiển thị báo cáo tự kiểm tra sức khỏe 5 hạng mục hệ thống (Day 10).
 * Thiết kế giao diện AMOLED Pure Black (#000000) tối giản chuẩn Fluent UI.
 */
class HealthCheckDialog(context: Context) : Dialog(context) {

    private val engine = HealthCheckEngine(context)
    private val scope = CoroutineScope(AndroidMainDispatcher)
    private var diagnosticJob: Job? = null

    private lateinit var llResultsContainer: LinearLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvStatusSummary: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // Root Dialog Container (AMOLED Pure Black Card)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050811"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = ViewGroup.LayoutParams(
                dp(340),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Header Title
        val tvTitle = TextView(context).apply {
            text = "🩺 KIỂM TRA SỨC KHỎE HỆ THỐNG"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        rootLayout.addView(tvTitle)

        // Subtitle
        val tvSub = TextView(context).apply {
            text = "Tự chẩn đoán 5 hạng mục then chốt (Bing, FGS, DB, Proxy, WebView)"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4), 0, dp(14))
        }
        rootLayout.addView(tvSub)

        // Loading Progress Bar
        pbLoading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        rootLayout.addView(pbLoading)

        // Status Summary
        tvStatusSummary = TextView(context).apply {
            text = "Đang kiểm tra các dịch vụ..."
            textSize = 12f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }
        rootLayout.addView(tvStatusSummary)

        // Results Container in ScrollView
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(240)
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        llResultsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(llResultsContainer)
        rootLayout.addView(scrollView)

        // Button Bar
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }

        btnRetry = Button(context).apply {
            text = "Thử Lại"
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { startCheck() }
        }
        btnBar.addView(btnRetry)

        btnClose = Button(context).apply {
            text = "Đóng"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0078D4"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { dismiss() }
        }
        btnBar.addView(btnClose)

        rootLayout.addView(btnBar)
        setContentView(rootLayout)

        startCheck()
    }

    private fun startCheck() {
        pbLoading.visibility = View.VISIBLE
        tvStatusSummary.text = "Đang kiểm tra các dịch vụ..."
        tvStatusSummary.setTextColor(Color.parseColor("#F8FAFC"))
        llResultsContainer.removeAllViews()

        diagnosticJob?.cancel()
        diagnosticJob = scope.launch {
            val report = engine.runFullDiagnostic()
            pbLoading.visibility = View.GONE

            if (report.overallSuccess) {
                tvStatusSummary.text = "✅ Hệ thống hoạt động hoàn hảo!"
                tvStatusSummary.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvStatusSummary.text = "⚠️ Phát hiện một số cảnh báo cần lưu ý!"
                tvStatusSummary.setTextColor(Color.parseColor("#F59E0B"))
            }

            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            report.items.forEach { item ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0B1120"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), when {
                            !item.isSuccess -> Color.parseColor("#7F1D1D")
                            item.isWarning -> Color.parseColor("#B45309")
                            else -> Color.parseColor("#1E293B")
                        })
                    }
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }
                }

                // Title row with icon
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val iconStr = when {
                    !item.isSuccess -> "❌"
                    item.isWarning -> "⚠️"
                    else -> "✅"
                }

                val tvItemTitle = TextView(context).apply {
                    text = "$iconStr ${item.title}"
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(when {
                        !item.isSuccess -> Color.parseColor("#EF4444")
                        item.isWarning -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#10B981")
                    })
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                titleRow.addView(tvItemTitle)

                if (item.latencyMs > 0) {
                    val tvLatency = TextView(context).apply {
                        text = "${item.latencyMs}ms"
                        textSize = 10f
                        setTextColor(Color.parseColor("#64748B"))
                    }
                    titleRow.addView(tvLatency)
                }

                card.addView(titleRow)

                val tvMessage = TextView(context).apply {
                    text = item.message
                    textSize = 11f
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setPadding(0, dp(4), 0, 0)
                }
                card.addView(tvMessage)

                llResultsContainer.addView(card)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        diagnosticJob?.cancel()
    }
}
