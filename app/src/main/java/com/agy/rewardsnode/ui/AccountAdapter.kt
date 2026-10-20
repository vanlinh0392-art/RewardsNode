package com.agy.rewardsnode.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.databinding.ItemAccountBinding

class AccountAdapter(
    private val onReAuthClicked: (AccountProfile) -> Unit,
    private val onEditProxyClicked: (AccountProfile) -> Unit,
    private val onDeleteClicked: (AccountProfile) -> Unit
) : ListAdapter<AccountProfile, AccountAdapter.AccountViewHolder>(AccountDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AccountViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AccountViewHolder(private val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(account: AccountProfile) {
            binding.tvAccountEmail.text = account.email
            binding.tvAccountLevelBadge.text = "Level ${account.level}"
            binding.tvAccountProgress.text = "Tiến độ hôm nay: ${account.completedSearchesToday}/${account.targetSearches} lượt (${account.completedSearchesToday * 3} điểm)"
            binding.pbAccountProgress.max = account.targetSearches
            binding.pbAccountProgress.progress = account.completedSearchesToday

            binding.tvAccountProxy.text = if (account.proxyUrl.isNullOrBlank()) {
                "Proxy: Không dùng (Direct IP)"
            } else {
                "Proxy: ${account.proxyUrl}"
            }

            binding.btnAuth.setOnClickListener { onReAuthClicked(account) }
            binding.btnEditProxy.setOnClickListener { onEditProxyClicked(account) }
            binding.btnDeleteAccount.setOnClickListener { onDeleteClicked(account) }
        }
    }

    class AccountDiffCallback : DiffUtil.ItemCallback<AccountProfile>() {
        override fun areItemsTheSame(oldItem: AccountProfile, newItem: AccountProfile): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AccountProfile, newItem: AccountProfile): Boolean = oldItem == newItem
    }
}
