package cn.moerain.ia64emu.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentVmDisplayBinding
import cn.moerain.ia64emu.runtime.QemuRuntime
import cn.moerain.ia64emu.runtime.RunState
import cn.moerain.ia64emu.runtime.RunStatus
import cn.moerain.ia64emu.util.Util
import cn.moerain.ia64emu.vnc.KeyMap
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VmDisplayFragment : Fragment() {

    private var _binding: FragmentVmDisplayBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private var config: VmConfig? = null
    private var lastConnectKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVmDisplayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vmId = arguments?.getString("vmId")
        config = vmId?.let { store.get(it) }
        val vm = config
        (activity as? AppCompatActivity)?.supportActionBar?.title = vm?.name ?: getString(R.string.nav_display)

        binding.vncView.statusListener = { message ->
            if (message.isNotBlank()) {
                showStatus(message, reconnect = true)
            }
        }
        binding.btnStartDisplay.setOnClickListener { startVm() }
        binding.btnStopDisplay.setOnClickListener { Util.stopVm(requireContext()) }
        binding.btnCtrlAltDel.setOnClickListener {
            binding.vncView.sendCombo(KeyMap.XK_CONTROL_L, KeyMap.XK_ALT_L, KeyMap.XK_DELETE)
        }
        binding.btnKeys.setOnClickListener {
            ExtraKeysDialog.show(requireContext(), binding.vncView)
        }
        binding.btnSerial.setOnClickListener {
            val id = config?.id ?: return@setOnClickListener
            findNavController().navigate(R.id.serialFragment, bundleOf("vmId" to id))
        }
        binding.btnExternal.setOnClickListener {
            val address = config?.let { Util.vncAddressString(it, QemuRuntime.current()) }
            if (address == null) {
                Util.toast(requireContext(), getString(R.string.display_not_running))
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.display_external)
                .setMessage(address)
                .setPositiveButton(R.string.display_copy_address) { _, _ ->
                    Util.copyText(requireContext(), "VNC", address)
                }
                .setNeutralButton(R.string.btn_close, null)
                .setNegativeButton(R.string.display_open_external) { _, _ ->
                    Util.openExternalVnc(requireContext(), address)
                }
                .show()
        }

        QemuRuntime.state.observe(viewLifecycleOwner) { state -> render(state) }

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render(state: RunState) {
        val vm = config ?: return
        val isThis = state.vmId == vm.id
        when {
            isThis && state.status == RunStatus.RUNNING -> {
                hideStatus()
                binding.btnExternal.visibility =
                    if (vm.exposeVnc) View.VISIBLE else View.GONE
                connectIfNeeded(vm, state)
            }
            isThis && (state.status == RunStatus.STARTING || state.status == RunStatus.STOPPING) -> {
                disconnect()
                showStatus(
                    state.message.ifBlank { getString(R.string.display_waiting) },
                    reconnect = false,
                    progress = true,
                )
            }
            isThis && state.status == RunStatus.ERROR -> {
                disconnect()
                showStatus(
                    state.message.ifBlank { getString(R.string.status_error) },
                    reconnect = true,
                )
            }
            else -> {
                disconnect()
                showStatus(getString(R.string.display_not_running), reconnect = false, startButton = true)
            }
        }
    }

    private fun connectIfNeeded(vm: VmConfig, state: RunState) {
        val endpoint = Util.vncEndpoint(vm, state)
        if (endpoint == null) {
            showStatus(getString(R.string.display_waiting), reconnect = false, progress = true)
            return
        }
        val key = "${endpoint.first}:${endpoint.second}"
        if (key == lastConnectKey) return
        lastConnectKey = key
        binding.vncView.connect(endpoint.first, endpoint.second)
    }

    private fun disconnect() {
        lastConnectKey = null
        binding.vncView.disconnect()
    }

    private fun showStatus(
        text: String,
        reconnect: Boolean,
        progress: Boolean = false,
        startButton: Boolean = false,
    ) {
        binding.statusPanel.visibility = View.VISIBLE
        binding.textStatus.text = text
        binding.progress.visibility = if (progress) View.VISIBLE else View.GONE
        binding.btnStartDisplay.visibility =
            if (startButton || reconnect) View.VISIBLE else View.GONE
        binding.btnStartDisplay.setText(
            if (reconnect) R.string.display_reconnect else R.string.display_start
        )
        if (reconnect) {
            binding.btnStartDisplay.setOnClickListener {
                val vm = config ?: return@setOnClickListener
                val state = QemuRuntime.current()
                if (state.status == RunStatus.RUNNING && state.vmId == vm.id) {
                    lastConnectKey = null
                    render(state)
                } else {
                    startVm()
                }
            }
        } else {
            binding.btnStartDisplay.setOnClickListener { startVm() }
        }
    }

    private fun hideStatus() {
        binding.statusPanel.visibility = View.GONE
    }

    private fun startVm() {
        val vm = config ?: return
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.vncView.disconnect()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }
}
