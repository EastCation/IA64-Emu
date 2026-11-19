package cn.moerain.ia64emu.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentSerialBinding
import java.io.File

class SerialFragment : Fragment() {

    private var _binding: FragmentSerialBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private val handler = Handler(Looper.getMainLooper())
    private var vmId: String? = null
    private var showQemuLog = false
    private var lastText: String? = null

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

        binding.btnTabSerial.setOnClickListener {
            showQemuLog = false
            lastText = null
            refresh()
            updateTabs()
        }
        binding.btnTabQemu.setOnClickListener {
            showQemuLog = true
            lastText = null
            refresh()
            updateTabs()
        }
        updateTabs()
        handler.post(poll)
    }

    private fun updateTabs() {
        val active = 0xFFFFFFFF.toInt()
        val inactive = 0x80FFFFFF.toInt()
        binding.btnTabSerial.setTextColor(if (showQemuLog) inactive else active)
        binding.btnTabQemu.setTextColor(if (showQemuLog) active else inactive)
    }

    private fun refresh() {
        val id = vmId ?: return
        val file: File = if (showQemuLog) store.qemuLogFile(id) else store.serialFile(id)
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
        super.onDestroyView()
        _binding = null
    }
}
