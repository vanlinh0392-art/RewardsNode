package com.agy.rewardsnode.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.agy.rewardsnode.R
import com.agy.rewardsnode.data.model.VlogEntry

/**
 * Adapter hiển thị stream log realtime dạng terminal siêu tốc, 100% Native Android SDK (BaseAdapter).
 * - Tối ưu 120Hz LTPO trên Xiaomi Mi 14: Tái sử dụng View mượt mà, không rò rỉ bộ nhớ.
 * - Cache màu sắc int cố định, triệt tiêu phân tích chuỗi màu tại runtime để duy trì 120 FPS.
 * - Phân loại màu sắc rõ ràng: ERR (đỏ), SRCH (tím), DOM (xanh), NET (lục), COOL (hổ phách).
 */
class VlogTerminalAdapter(
    private val onItemClick: ((VlogEntry) -> Unit)? = null
) : BaseAdapter() {

    companion object {
        private val COLOR_TAG_ERR = Color.parseColor("#EF4444")
        private val COLOR_TAG_SRCH = Color.parseColor("#8B5CF6")
        private val COLOR_TAG_DOM = Color.parseColor("#3B82F6")
        private val COLOR_TAG_NET = Color.parseColor("#10B981")
        private val COLOR_TAG_COOL = Color.parseColor("#F59E0B")
        private val COLOR_TAG_CAPT = Color.parseColor("#EC4899")
        private val COLOR_TAG_SYS = Color.parseColor("#64748B")
        private val COLOR_TAG_DEFAULT = Color.parseColor("#475569")

        private val COLOR_MSG_ERR = Color.parseColor("#EF4444")
        private val COLOR_MSG_WARN = Color.parseColor("#F59E0B")
        private val COLOR_MSG_NORMAL = Color.parseColor("#E2E8F0")
    }

    private var logs: List<VlogEntry> = emptyList()

    fun submitList(newLogs: List<VlogEntry>, onFinished: (() -> Unit)? = null) {
        this.logs = newLogs
        notifyDataSetChanged()
        onFinished?.invoke()
    }

    override fun getCount(): Int = logs.size

    override fun getItem(position: Int): VlogEntry = logs[position]

    override fun getItemId(position: Int): Long = logs[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: ViewHolder

        if (convertView == null) {
            view = LayoutInflater.from(parent.context).inflate(R.layout.item_vlog, parent, false)
            holder = ViewHolder(view)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        holder.bind(getItem(position))
        return view
    }

    inner class ViewHolder(private val itemView: View) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvVlogTime)
        private val tvTag: TextView = itemView.findViewById(R.id.tvVlogTag)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvVlogMessage)

        fun bind(entry: VlogEntry) {
            tvTime.text = entry.timeFormatted
            tvTag.text = entry.tag

            // Đổi màu nền tag badge linh hoạt
            val tagColor = when (entry.tag.uppercase()) {
                "ERR" -> COLOR_TAG_ERR
                "SRCH" -> COLOR_TAG_SRCH
                "DOM" -> COLOR_TAG_DOM
                "NET" -> COLOR_TAG_NET
                "COOL" -> COLOR_TAG_COOL
                "CAPT" -> COLOR_TAG_CAPT
                "SYS" -> COLOR_TAG_SYS
                else -> COLOR_TAG_DEFAULT
            }

            val bg = tvTag.background
            if (bg is GradientDrawable) {
                bg.setColor(tagColor)
            } else {
                val newBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor(tagColor)
                }
                tvTag.background = newBg
            }

            // Text message với màu cảnh báo phù hợp
            val msgColor = when {
                entry.level == "ERROR" || entry.tag == "ERR" -> COLOR_MSG_ERR
                entry.level == "WARN" || entry.tag == "COOL" -> COLOR_MSG_WARN
                else -> COLOR_MSG_NORMAL
            }
            tvMessage.setTextColor(msgColor)

            val builder = StringBuilder(entry.message)
            if (!entry.details.isNullOrBlank()) {
                builder.append(" [").append(entry.details).append("]")
            }
            if (entry.snapshotId != null) {
                builder.append(" 📸 [Snapshot]")
            }
            tvMessage.text = builder.toString()

            if (onItemClick != null) {
                itemView.setOnClickListener { onItemClick.invoke(entry) }
            } else {
                itemView.setOnClickListener(null)
            }
        }
    }
}
