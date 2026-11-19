package cn.moerain.ia64emu.ui

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.bootstrap.BootstrapListener
import cn.moerain.ia64emu.bootstrap.BootstrapManifest
import cn.moerain.ia64emu.data.AppSettings
import cn.moerain.ia64emu.databinding.DialogBootstrapBinding
import cn.moerain.ia64emu.util.Util
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BootstrapDialogFragment : DialogFragment() {

    private var _binding: DialogBootstrapBinding? = null
    private val binding get() = _binding!!

    private val settings by lazy { AppSettings(requireContext()) }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installFromUri(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogBootstrapBinding.inflate(layoutInflater)
        binding.bootstrapUrlInput.setText(settings.bootstrapUrl)

        binding.btnCancelDialog.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnChooseZipDialog.setOnClickListener {
            if (isInstalling) return@setOnClickListener
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        binding.btnInstallDialog.setOnClickListener {
            if (isInstalling) return@setOnClickListener
            val url = binding.bootstrapUrlInput.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                binding.bootstrapUrlInput.error =
                    getString(R.string.download_url_label)
                return@setOnClickListener
            }
            settings.bootstrapUrl = url
            installFromUrl(url)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_install_title)
            .setView(binding.root)
            .create()
    }

    private var isInstalling = false

    private fun startProgress() {
        isInstalling = true
        binding.btnInstallDialog.isEnabled = false
        binding.btnChooseZipDialog.isEnabled = false
        binding.btnCancelDialog.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        binding.progress.isIndeterminate = true
        binding.progressStatus.visibility = android.view.View.VISIBLE
        binding.progressStatus.text = getString(R.string.installing)
        dialog?.setCancelable(false)
    }

    private fun finishProgress() {
        isInstalling = false
        binding.btnInstallDialog.isEnabled = true
        binding.btnChooseZipDialog.isEnabled = true
        binding.btnCancelDialog.isEnabled = true
        binding.progress.visibility = android.view.View.GONE
        binding.progressStatus.visibility = android.view.View.GONE
        dialog?.setCancelable(true)
    }

    private fun installFromUrl(url: String) {
        startProgress()
        Bootstrap.installFromUrlAsync(requireContext().applicationContext, url, listener)
    }

    private fun installFromUri(uri: Uri) {
        startProgress()
        Bootstrap.installFromUriAsync(requireContext().applicationContext, uri, listener)
    }

    private val listener = object : BootstrapListener {
        override fun onProgress(message: String, fraction: Int) {
            val b = _binding ?: return
            b.progressStatus.text = message
            if (fraction >= 0) {
                b.progress.isIndeterminate = false
                b.progress.setProgressCompat(fraction.coerceIn(0, 100), true)
            } else {
                b.progress.isIndeterminate = true
            }
        }

        override fun onSuccess(manifest: BootstrapManifest) {
            val context = context ?: return
            Util.toast(context, context.getString(R.string.install_success))
            parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle())
            dismissAllowingStateLoss()
        }

        override fun onError(error: Exception) {
            val context = context ?: return
            Util.toast(
                context,
                context.getString(R.string.install_failed, error.message ?: error.javaClass.simpleName),
            )
            runCatching { finishProgress() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "bootstrap_installed"
        const val TAG = "BootstrapDialog"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            BootstrapDialogFragment().show(fragmentManager, TAG)
        }
    }
}
