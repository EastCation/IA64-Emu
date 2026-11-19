package cn.moerain.ia64emu.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentSerialBinding
import cn.moerain.ia64emu.runtime.MonitorClient
import java.io.File

class SerialFragment : Fragment() {

    private var _binding: FragmentSerialBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private val handler = Handler(Looper.getMainLooper())
    private var vmId: String? = null
    private var mode = Mode.SERIAL
    private var lastText: String? = null
    private var monitor: MonitorClient? = null
    private val monitorText = StringBuilder()

    private enum class Mode { SERIAL, QEMU, MONITOR }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSerialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vmId = arguments?.getString("vmId")
        val name = vmId?.let { store.get(it)?.name }
        (activity as? AppCompatActivity)?.supportActionBar?.title = name ?: getString(R.string.nav_serial)

        binding.btnTabSerial.setOnClickListener { switchMode(Mode.SERIAL) }
        binding.btnTabQemu.setOnClickListener { switchMode(Mode.QEMU) }
        binding.btnTabMonitor.setOnClickListener { switchMode(Mode.MONITOR) }
        binding.btnSendMonitor.setOnClickListener { sendMonitorCommand() }
        binding.monitorCommand.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendMonitorCommand()
                true
            } else {
                false
            }
        }

        switchMode(Mode.SERIAL)
        handler.post(poll)
    }

    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        if (mode == Mode.MONITOR) closeMonitor()
        mode = newMode
        lastText = null
        binding.monitorInputBar.visibility =
            if (mode == Mode.MONITOR) View.VISIBLE else View.GONE
        binding.textSerialHint.setText(
            if (mode == Mode.MONITOR) R.string.monitor_hint else R.string.serial_hint
        )
        updateTabs()
        if (mode == Mode.MONITOR) {
            openMonitor()
        } else {
            refresh()
        }
    }

    private fun updateTabs() {
        val active = 0xFFFFFFFF.toInt()
        val inactive = 0x80FFFFFF.toInt()
        binding.btnTabSerial.setTextColor(if (mode == Mode.SERIAL) active else inactive)
        binding.btnTabQemu.setTextColor(if (mode == Mode.QEMU) active else inactive)
        binding.btnTabMonitor.setTextColor(if (mode == Mode.MONITOR) active else inactive)
    }

    private fun openMonitor() {
        val id = vmId ?: return
        val socket = store.monitorSocket(id)
        monitorText.setLength(0)
        if (!socket.exists()) {
            monitorText.append(getString(R.string.monitor_not_running))
            binding.logText.text = monitorText
            return
        }
        monitorText.append(getString(R.string.monitor_connected)).append('\n')
        binding.logText.text = monitorText
        val client = MonitorClient(socket) { text -> appendMonitor(text) }
        monitor = client
        client.connect()
    }

    private fun closeMonitor() {
        monitor?.close()
        monitor = null
        monitorText.setLength(0)
    }

    private fun appendMonitor(text: String) {
        if (mode != Mode.MONITOR) return
        monitorText.append(text)
        if (monitorText.length > 300_000) {
            monitorText.delete(0, monitorText.length - 250_000)
        }
        binding.logText.text = monitorText
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMonitorCommand() {
        val command = binding.monitorCommand.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return
        binding.monitorCommand.setText("")
        appendMonitor("(qemu) $command\n")
        monitor?.send(command)
    }

    private fun refresh() {
        if (mode == Mode.MONITOR) return
        val id = vmId ?: return
        val file: File = if (mode == Mode.QEMU) store.qemuLogFile(id) else store.serialFile(id)
        val text = if (file.isFile) tail(file, 256 * 1024) else ""
        val display = if (text.isBlank()) getString(R.string.serial_empty) else text
        if (display == lastText) return
        lastText = display
        val atBottom = !binding.logScroll.canScrollVertically(1)
        binding.logText.text = display
        if (atBottom) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun tail(file: File, maxBytes: Int): String {
        return runCatching {
            val length = file.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                buffer.toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    override fun onDestroyView() {
        handler.removeCallbacks(poll)
        closeMonitor()
        super.onDestroyView()
        _binding = null
    }
}
