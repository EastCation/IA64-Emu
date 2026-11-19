package cn.moerain.ia64emu.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.databinding.ItemVmBinding
import cn.moerain.ia64emu.runtime.RunState
import cn.moerain.ia64emu.runtime.RunStatus
import cn.moerain.ia64emu.util.Util

class VmListAdapter(
    private val callbacks: Callbacks,
) : ListAdapter<VmConfig, VmListAdapter.Holder>(Diff) {

    interface Callbacks {
        fun onStart(vm: VmConfig)
        fun onStop(vm: VmConfig)
        fun onDisplay(vm: VmConfig)
        fun onEdit(vm: VmConfig)
        fun onDelete(vm: VmConfig)
    }

    private var runtime: RunState = RunState()

    fun setRuntime(state: RunState) {
        runtime = state
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), runtime)
    }

    inner class Holder(private val binding: ItemVmBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(vm: VmConfig, state: RunState) {
            val context = binding.root.context
            binding.textName.text = vm.name
            binding.textSummary.text = vm.summary()

            val running = state.vmId == vm.id &&
                (state.status == RunStatus.RUNNING || state.status == RunStatus.STARTING)
            val status = when {
                running -> state.status
                state.vmId == vm.id && state.status == RunStatus.ERROR -> RunStatus.ERROR
                else -> RunStatus.IDLE
            }
            binding.textStatus.text = Util.statusText(context, status)
            binding.textStatus.setTextColor(Util.statusColor(context, status))

            binding.btnStart.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
            binding.btnStart.contentDescription =
                context.getString(if (running) R.string.btn_stop else R.string.btn_start)
            binding.btnStart.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (running) R.color.status_error else R.color.primary,
                )
            )

            binding.btnStart.setOnClickListener {
                if (running) callbacks.onStop(vm) else callbacks.onStart(vm)
            }
            binding.btnDisplay.setOnClickListener { callbacks.onDisplay(vm) }
            binding.btnEdit.setOnClickListener { callbacks.onEdit(vm) }
            binding.btnDelete.setOnClickListener { callbacks.onDelete(vm) }
            binding.root.setOnClickListener { callbacks.onEdit(vm) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<VmConfig>() {
        override fun areItemsTheSame(oldItem: VmConfig, newItem: VmConfig): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: VmConfig, newItem: VmConfig): Boolean =
            oldItem == newItem
    }
}
