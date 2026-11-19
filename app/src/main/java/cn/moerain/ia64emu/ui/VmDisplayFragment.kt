package cn.moerain.ia64emu.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentVmDisplayBinding
import cn.moerain.ia64emu.runtime.QemuRuntime
import cn.moerain.ia64emu.runtime.QmpClient
import cn.moerain.ia64emu.runtime.RunState
import cn.moerain.ia64emu.runtime.RunStatus
import cn.moerain.ia64emu.util.FileImporter
import cn.moerain.ia64emu.util.Util
import cn.moerain.ia64emu.vnc.KeyMap
import cn.moerain.ia64emu.vnc.VncInputView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VmDisplayFragment : Fragment(), MenuProvider {

    private var _binding: FragmentVmDisplayBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private var config: VmConfig? = null
    private var lastConnectKey: String? = null

    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importIso(uri)
    }

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
            if (message.isBlank()) {
                hideStatus()
            } else {
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
        binding.vncInput.sink = object : VncInputView.Sink {
            override fun sendKey(keysym: Int, down: Boolean) {
                binding.vncView.sendKey(keysym, down)
            }

            override fun sendText(text: String) {
                binding.vncView.sendText(text)
            }
        }
        binding.btnIme.setOnClickListener { binding.vncInput.toggleIme() }
        binding.vncView.setOnTouchListener { _, _ ->
            binding.vncInput.hideIme()
            false
        }
        binding.btnTextInput.setOnClickListener { showTextInputDialog() }
        binding.btnMedia.setOnClickListener { showMediaDialog() }
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

        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_display, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        val vm = config ?: return false
        return when (item.itemId) {
            R.id.action_ime -> {
                binding.vncInput.toggleIme()
                true
            }
            R.id.action_text_input -> {
                showTextInputDialog()
                true
            }
            R.id.action_serial -> {
                findNavController().navigate(R.id.serialFragment, bundleOf("vmId" to vm.id))
                true
            }
            R.id.action_media -> {
                showMediaDialog()
                true
            }
            R.id.action_stop -> {
                Util.stopVm(requireContext())
                true
            }
            else -> false
        }
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

    private fun showTextInputDialog() {
        val context = requireContext()
        val layout = TextInputLayout(context).apply {
            hint = context.getString(R.string.text_input_hint)
        }
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 6
        }
        layout.addView(input)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.text_input_title)
            .setMessage(R.string.text_input_note)
            .setView(layout)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    binding.vncView.sendText(text)
                }
                binding.vncView.requestFocus()
            }
            .show()
    }

    private fun showMediaDialog() {
        val vm = config ?: return
        val state = QemuRuntime.current()
        if (state.status != RunStatus.RUNNING || state.vmId != vm.id) {
            Util.toast(requireContext(), getString(R.string.media_not_running))
            return
        }
        val current = vm.cdrom.ifBlank { getString(R.string.media_none) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.media_title)
            .setMessage(getString(R.string.media_current, current))
            .setNegativeButton(R.string.btn_cancel, null)
            .setNeutralButton(R.string.media_eject) { _, _ -> ejectMedia(vm) }
            .setPositiveButton(R.string.media_change) { _, _ ->
                pickIso.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun importIso(uri: android.net.Uri) {
        val vm = config ?: return
        Util.toast(requireContext(), getString(R.string.media_importing))
        FileImporter.importAsync(requireContext(), store, vm.id, uri) { imported ->
            if (!isAdded) return@importAsync
            imported.onSuccess { hostPath ->
                val appContext = requireContext().applicationContext
                QmpClient(appContext, store).changeMedium(vm.id, hostPath) { result ->
                    if (!isAdded) return@changeMedium
                    result.onSuccess {
                        val updated = vm.copy(cdrom = hostPath)
                        store.save(updated)
                        config = updated
                        Util.toast(requireContext(), getString(R.string.media_changed))
                    }.onFailure { error ->
                        Util.toast(
                            requireContext(),
                            getString(R.string.media_failed, error.message ?: ""),
                        )
                    }
                }
            }.onFailure { error ->
                Util.toast(
                    requireContext(),
                    getString(R.string.media_failed, error.message ?: ""),
                )
            }
        }
    }

    private fun ejectMedia(vm: VmConfig) {
        val appContext = requireContext().applicationContext
        QmpClient(appContext, store).eject(vm.id) { result ->
            if (!isAdded) return@eject
            result.onSuccess {
                val updated = vm.copy(cdrom = "")
                store.save(updated)
                config = updated
                Util.toast(requireContext(), getString(R.string.media_ejected))
            }.onFailure { error ->
                Util.toast(
                    requireContext(),
                    getString(R.string.media_failed, error.message ?: ""),
                )
            }
        }
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
        requireActivity().removeMenuProvider(this)
        binding.vncInput.hideIme()
        super.onDestroyView()
        binding.vncView.disconnect()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }
}
