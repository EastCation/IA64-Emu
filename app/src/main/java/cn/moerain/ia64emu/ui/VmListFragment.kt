package cn.moerain.ia64emu.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentVmListBinding
import cn.moerain.ia64emu.runtime.QemuRuntime
import cn.moerain.ia64emu.runtime.RunStatus
import cn.moerain.ia64emu.util.Util
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VmListFragment : Fragment() {

    private var _binding: FragmentVmListBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private lateinit var adapter: VmListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVmListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = VmListAdapter(object : VmListAdapter.Callbacks {
            override fun onStart(vm: VmConfig) = startVm(vm)
            override fun onStop(vm: VmConfig) = Util.stopVm(requireContext())
            override fun onDisplay(vm: VmConfig) = openDisplay(vm)
            override fun onEdit(vm: VmConfig) = openEditor(vm.id)
            override fun onDelete(vm: VmConfig) = confirmDelete(vm)
        })
        binding.vmList.layoutManager = LinearLayoutManager(requireContext())
        binding.vmList.adapter = adapter

        binding.fabAddVm.setOnClickListener { openEditor(null) }
        binding.btnBootstrap.setOnClickListener {
            if (Bootstrap.isInstalled(requireContext())) {
                findNavController().navigate(R.id.settingsFragment)
            } else {
                BootstrapDialogFragment.show(parentFragmentManager)
            }
        }

        QemuRuntime.state.observe(viewLifecycleOwner) { state ->
            adapter.setRuntime(state)
        }

        parentFragmentManager.setFragmentResultListener(
            BootstrapDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, _ -> refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val vms = store.list()
        adapter.submitList(vms)
        binding.emptyView.visibility = if (vms.isEmpty()) View.VISIBLE else View.GONE
        updateBootstrapCard()
        QemuRuntime.state.value?.let { adapter.setRuntime(it) }
    }

    private fun updateBootstrapCard() {
        val manifest = Bootstrap.manifest(requireContext())
        if (Bootstrap.isInstalled(requireContext()) && manifest != null) {
            binding.bootstrapTitle.setText(R.string.bootstrap_ready_title)
            binding.bootstrapBody.text = getString(
                R.string.bootstrap_status_installed,
                manifest.qemuVersion,
                manifest.abi,
            )
            binding.btnBootstrap.setText(R.string.action_settings)
        } else {
            binding.bootstrapTitle.setText(R.string.bootstrap_missing_title)
            binding.bootstrapBody.setText(R.string.bootstrap_missing_body)
            binding.btnBootstrap.setText(R.string.install_bootstrap)
        }
    }

    private fun startVm(vm: VmConfig) {
        if (Bootstrap.supportedAbi() == null) {
            Util.toast(requireContext(), getString(R.string.error_no_abi))
            return
        }
        if (!Bootstrap.isInstalled(requireContext())) {
            Util.toast(requireContext(), getString(R.string.msg_install_first))
            BootstrapDialogFragment.show(parentFragmentManager)
            return
        }
        val state = QemuRuntime.current()
        val busy = state.status == RunStatus.STARTING ||
            state.status == RunStatus.RUNNING ||
            state.status == RunStatus.STOPPING
        if (busy && state.vmId != vm.id) {
            Util.toast(requireContext(), getString(R.string.msg_vm_running))
            return
        }
        Util.startVm(requireContext(), vm.id)
        openDisplay(vm)
    }

    private fun openDisplay(vm: VmConfig) {
        findNavController().navigate(
            R.id.vmDisplayFragment,
            bundleOf("vmId" to vm.id),
        )
    }

    private fun openEditor(vmId: String?) {
        val args = Bundle()
        if (vmId != null) args.putString("vmId", vmId)
        findNavController().navigate(R.id.vmEditFragment, args)
    }

    private fun confirmDelete(vm: VmConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_body)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                if (QemuRuntime.isRunning(vm.id)) {
                    Util.stopVm(requireContext())
                }
                store.delete(vm.id)
                Util.toast(requireContext(), getString(R.string.delete_done))
                refresh()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.vmList.adapter = null
        _binding = null
    }
}
