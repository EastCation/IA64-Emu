package cn.moerain.ia64emu.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.data.Cpus
import cn.moerain.ia64emu.data.Machines
import cn.moerain.ia64emu.data.StorageBuses
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentVmEditBinding
import cn.moerain.ia64emu.runtime.QemuRuntime
import cn.moerain.ia64emu.util.FileImporter
import cn.moerain.ia64emu.util.Util
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

class VmEditFragment : Fragment() {

    private var _binding: FragmentVmEditBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private val main = Handler(Looper.getMainLooper())
    private var config: VmConfig? = null
    private var existing = false

    private enum class PickTarget { DISK1, DISK2, CDROM }

    private var pickTarget = PickTarget.DISK1

    private val videoLabels = arrayOf("ATI Radeon", "NVIDIA Quadro2")

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVmEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getString("vmId")
        val loaded = if (id != null) store.get(id) else null
        existing = loaded != null
        config = loaded ?: VmConfig.newForMachine("itanium2-vpc")

        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(if (existing) R.string.nav_vm_edit else R.string.nav_vm_new)

        setupDropdowns()
        populate()
        setupListeners()
    }

    private fun setupDropdowns() {
        binding.dropdownMachine.setSimpleItems(Machines.labels())
        binding.dropdownVideo.setSimpleItems(videoLabels)
        binding.dropdownAti.setSimpleItems(Cpus.vgaModels.toTypedArray())
        binding.dropdownNic.setSimpleItems(arrayOf("e1000", "e1000e", "rtl8139"))

        binding.dropdownMachine.setOnItemClickListener { _, _, position, _ ->
            val machine = Machines.all[position]
            binding.dropdownCpu.setSimpleItems(Cpus.forMachine(machine.id).toTypedArray())
            binding.dropdownCpu.setText(machine.defaultCpu, false)
            updateStorageBusItems(machine.id)
            binding.sliderRam.value = clampRam(machine.defaultRamMb)
            val max = machine.maxCpus.coerceAtMost(16)
            binding.sliderCpus.valueTo = max.toFloat().coerceAtLeast(2f)
            if (binding.sliderCpus.value > max) binding.sliderCpus.value = max.toFloat()
            updateRamLabel()
            updateCpuLabel()
        }
        binding.dropdownVideo.setOnItemClickListener { _, _, position, _ ->
            val ati = position == 0
            binding.dropdownAti.isEnabled = ati
            binding.editWidth.isEnabled = ati
            binding.editHeight.isEnabled = ati
        }
    }

    private fun populate() {
        val vm = config ?: return
        binding.editName.setText(vm.name)
        val machineInfo = Machines.find(vm.machine)
        binding.dropdownMachine.setText(machineInfo.label, false)
        val cpus = Cpus.forMachine(machineInfo.id)
        binding.dropdownCpu.setSimpleItems(cpus.toTypedArray())
        binding.dropdownCpu.setText(if (cpus.contains(vm.cpu)) vm.cpu else machineInfo.defaultCpu, false)
        updateStorageBusItems(machineInfo.id, vm.storageBus)
        binding.sliderRam.value = clampRam(vm.memoryMb)
        val maxCpus = Machines.find(vm.machine).maxCpus.coerceAtMost(16).coerceAtLeast(1)
        binding.sliderCpus.valueTo = maxCpus.toFloat().coerceAtLeast(2f)
        binding.sliderCpus.value = vm.cpus.coerceIn(1, maxCpus).toFloat()
        binding.dropdownVideo.setText(
            if (vm.vga.equals("quadro2", true)) videoLabels[1] else videoLabels[0],
            false,
        )
        binding.dropdownAti.setText(vm.atiModel, false)
        binding.editWidth.setText(vm.xres.toString())
        binding.editHeight.setText(vm.yres.toString())
        binding.editDisk1.setText(vm.disk1)
        binding.editDisk2.setText(vm.disk2)
        binding.editCdrom.setText(vm.cdrom)
        binding.switchNetwork.isChecked = vm.nicEnabled
        binding.dropdownNic.setText(vm.nicModel, false)
        binding.switchSerial.isChecked = vm.serialConsole
        binding.switchExposeVnc.isChecked = vm.exposeVnc
        binding.editVncAddress.setText(vm.vncAddress)
        binding.editExtraArgs.setText(vm.extraArgs)
        binding.btnDeleteVm.visibility = if (existing) View.VISIBLE else View.GONE
        val ati = !vm.vga.equals("quadro2", true)
        binding.dropdownAti.isEnabled = ati
        binding.editWidth.isEnabled = ati
        binding.editHeight.isEnabled = ati
        updateRamLabel()
        updateCpuLabel()
    }

    private fun setupListeners() {
        binding.sliderRam.addOnChangeListener { _, _, _ -> updateRamLabel() }
        binding.sliderCpus.addOnChangeListener { _, _, _ -> updateCpuLabel() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnCancel.setOnClickListener { findNavController().navigateUp() }
        binding.btnDeleteVm.setOnClickListener { confirmDelete() }
        binding.btnDisk1.setOnClickListener {
            pickTarget = PickTarget.DISK1
            pickFile.launch(arrayOf("*/*"))
        }
        binding.btnDisk2.setOnClickListener {
            pickTarget = PickTarget.DISK2
            pickFile.launch(arrayOf("*/*"))
        }
        binding.btnCdrom.setOnClickListener {
            pickTarget = PickTarget.CDROM
            pickFile.launch(arrayOf("*/*"))
        }
        binding.btnCreateDisk.setOnClickListener { showCreateDiskDialog() }
    }

    private fun showCreateDiskDialog() {
        val vm = config ?: return
        val context = requireContext()
        val hasDisk1 = binding.editDisk1.text?.toString()?.isNotBlank() == true
        val hasDisk2 = binding.editDisk2.text?.toString()?.isNotBlank() == true
        if (hasDisk1 && hasDisk2) {
            Util.toast(context, getString(R.string.disk_slots_full))
            return
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val nameLayout = TextInputLayout(context).apply {
            hint = getString(R.string.label_disk_name)
        }
        val nameInput = TextInputEditText(context).apply {
            setText("disk.img")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        nameLayout.addView(nameInput)
        val sizeLayout = TextInputLayout(context).apply {
            hint = getString(R.string.label_disk_size_gb)
        }
        val sizeInput = TextInputEditText(context).apply {
            setText("8")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        sizeLayout.addView(sizeInput)
        container.addView(nameLayout)
        container.addView(sizeLayout)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_new_disk)
            .setView(container)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_create) { _, _ ->
                val rawName = nameInput.text?.toString()?.trim().orEmpty()
                val sizeGb = sizeInput.text?.toString()?.toIntOrNull() ?: 0
                if (sizeGb !in 1..2048) {
                    Util.toast(context, getString(R.string.disk_size_invalid))
                    return@setPositiveButton
                }
                if (rawName.isBlank()) {
                    Util.toast(context, getString(R.string.label_disk_name))
                    return@setPositiveButton
                }
                val name = if (rawName.contains('.')) rawName else "$rawName.img"
                val dir = store.importsDir(vm.id)
                val target = File(dir, name)
                if (target.exists()) {
                    Util.toast(context, getString(R.string.disk_exists))
                    return@setPositiveButton
                }
                Thread {
                    val result = runCatching {
                        RandomAccessFile(target, "rw").use { it.setLength(sizeGb * 1024L * 1024L * 1024L) }
                        target.absolutePath
                    }
                    main.post {
                        if (!isAdded) return@post
                        result.onSuccess { path ->
                            if (hasDisk1) binding.editDisk2.setText(path)
                            else binding.editDisk1.setText(path)
                            Util.toast(requireContext(), getString(R.string.disk_created))
                        }.onFailure {
                            Util.toast(
                                requireContext(),
                                it.message ?: it.javaClass.simpleName,
                            )
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun updateStorageBusItems(machineId: String, preferred: String = "auto") {
        val options = StorageBuses.optionsFor(machineId)
        binding.dropdownStorageBus.setSimpleItems(options.map { it.label }.toTypedArray())
        val id = if (options.any { it.id == preferred }) preferred else "auto"
        binding.dropdownStorageBus.setText(StorageBuses.labelFor(machineId, id), false)
    }

    private fun updateRamLabel() {
        binding.textRamValue.text =
            getString(R.string.label_memory) + ": " + binding.sliderRam.value.roundToInt() + " MiB"
    }

    private fun updateCpuLabel() {
        binding.textCpusValue.text =
            getString(R.string.label_cpus) + ": " + binding.sliderCpus.value.roundToInt()
    }

    private fun clampRam(value: Int): Float {
        val rounded = ((value / 256f).roundToInt() * 256).coerceIn(512, 32768)
        return rounded.toFloat()
    }

    private fun importFile(uri: Uri) {
        val vm = config ?: return
        val name = FileImporter.displayName(requireContext(), uri) ?: getString(R.string.msg_unsupported_file)
        Util.toast(requireContext(), getString(R.string.msg_file_copying, name))
        FileImporter.importAsync(requireContext(), store, vm.id, uri) { result ->
            if (!isAdded) return@importAsync
            result.onSuccess { path ->
                when (pickTarget) {
                    PickTarget.DISK1 -> binding.editDisk1.setText(path)
                    PickTarget.DISK2 -> binding.editDisk2.setText(path)
                    PickTarget.CDROM -> binding.editCdrom.setText(path)
                }
                Util.toast(requireContext(), getString(R.string.msg_file_imported))
            }.onFailure { error ->
                Util.toast(
                    requireContext(),
                    getString(R.string.msg_file_failed) + ": " + (error.message ?: ""),
                )
            }
        }
    }

    private fun save() {
        val vm = config ?: return
        val name = binding.editName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.editName.error = getString(R.string.label_name)
            return
        }
        val machine = Machines.find(Machines.idForLabel(binding.dropdownMachine.text.toString()))
        val cpu = binding.dropdownCpu.text?.toString()?.trim().orEmpty()
        if (cpu.isEmpty() || !Cpus.forMachine(machine.id).contains(cpu)) {
            binding.dropdownCpu.error = getString(R.string.label_cpu)
            return
        }
        val width = alignWidth(binding.editWidth.text?.toString()?.toIntOrNull() ?: vm.xres)
        val height = (binding.editHeight.text?.toString()?.toIntOrNull() ?: vm.yres)
            .coerceIn(480, 4096)
        val vga = if (binding.dropdownVideo.text.toString().startsWith("NVIDIA")) {
            "quadro2"
        } else {
            "ati"
        }
        val updated = vm.copy(
            name = name,
            machine = machine.id,
            cpu = cpu,
            memoryMb = binding.sliderRam.value.roundToInt(),
            cpus = binding.sliderCpus.value.roundToInt().coerceIn(1, machine.maxCpus.coerceAtMost(16)),
            vga = vga,
            atiModel = binding.dropdownAti.text?.toString()?.ifBlank { "rv100" } ?: "rv100",
            xres = width,
            yres = height,
            disk1 = binding.editDisk1.text?.toString()?.trim().orEmpty(),
            disk2 = binding.editDisk2.text?.toString()?.trim().orEmpty(),
            cdrom = binding.editCdrom.text?.toString()?.trim().orEmpty(),
            storageBus = StorageBuses.idForLabel(
                machine.id,
                binding.dropdownStorageBus.text?.toString().orEmpty(),
            ),
            nicEnabled = binding.switchNetwork.isChecked,
            nicModel = binding.dropdownNic.text?.toString()?.ifBlank { "e1000" } ?: "e1000",
            serialConsole = binding.switchSerial.isChecked,
            exposeVnc = binding.switchExposeVnc.isChecked,
            vncAddress = binding.editVncAddress.text?.toString()?.trim().orEmpty(),
            extraArgs = binding.editExtraArgs.text?.toString()?.trim().orEmpty(),
        )
        if (binding.switchResetNvram.isChecked) {
            store.nvramFile(vm.id).delete()
            Util.toast(requireContext(), getString(R.string.msg_nvram_reset))
        }
        store.save(updated)
        Util.toast(requireContext(), getString(R.string.saved))
        findNavController().navigateUp()
    }

    private fun alignWidth(width: Int): Int {
        val aligned = (width / 8) * 8
        return aligned.coerceIn(640, 4096)
    }

    private fun confirmDelete() {
        val vm = config ?: return
        if (!existing) {
            findNavController().navigateUp()
            return
        }
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
                findNavController().navigateUp()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
