package cn.moerain.ia64emu.ui

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.bootstrap.BootstrapListener
import cn.moerain.ia64emu.bootstrap.BootstrapManifest
import cn.moerain.ia64emu.data.VmStore
import cn.moerain.ia64emu.databinding.FragmentSettingsBinding
import cn.moerain.ia64emu.util.Util
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { VmStore(requireContext()) }
    private val main = Handler(Looper.getMainLooper())

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installFromUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnInstallUrl.setOnClickListener {
            BootstrapDialogFragment.show(parentFragmentManager)
        }
        binding.btnInstallZip.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        binding.btnDeleteBootstrap.setOnClickListener { confirmDeleteBootstrap() }
        binding.btnReinstallProot.setOnClickListener { reinstallProot() }
        binding.btnAllFiles.setOnClickListener { Util.requestAllFilesAccess(requireContext()) }

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
        val context = requireContext()
        val abi = Bootstrap.supportedAbi()
        if (abi == null) {
            binding.textProotStatus.setText(R.string.proot_unsupported)
            binding.btnInstallUrl.isEnabled = false
            binding.btnInstallZip.isEnabled = false
        } else {
            binding.btnInstallUrl.isEnabled = true
            binding.btnInstallZip.isEnabled = true
        }

        val manifest = Bootstrap.manifest(context)
        if (Bootstrap.isInstalled(context) && manifest != null) {
            binding.textBootstrapStatus.text = getString(
                R.string.bootstrap_status_installed,
                manifest.qemuVersion,
                manifest.abi,
            )
            binding.textBootstrapAbi.text = "${manifest.abi} · ${manifest.builtAt}"
        } else {
            binding.textBootstrapStatus.setText(R.string.bootstrap_status_missing)
            binding.textBootstrapAbi.text = abi?.let { getString(R.string.bootstrap_status_abi, it) } ?: ""
        }

        val prootAbi = Bootstrap.supportedAbi()
        binding.textProotStatus.text = if (Bootstrap.isProotInstalled(context)) {
            getString(R.string.proot_status_installed, prootAbi ?: "?")
        } else {
            getString(R.string.proot_status_missing)
        }

        binding.textStorage.text = getString(
            R.string.storage_usage,
            Bootstrap.humanSize(Bootstrap.installedSize(context)),
            Bootstrap.humanSize(store.diskUsageBytes()),
        )

        binding.btnAllFiles.visibility =
            if (!Util.hasAllFilesAccess() && android.os.Build.VERSION.SDK_INT >= 30) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun confirmDeleteBootstrap() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_bootstrap)
            .setMessage(R.string.bootstrap_missing_body)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                Bootstrap.delete(requireContext())
                Util.toast(requireContext(), getString(R.string.delete_done))
                refresh()
            }
            .show()
    }

    private fun reinstallProot() {
        val context = requireContext().applicationContext
        Thread {
            val result = Bootstrap.ensureProot(context, force = true)
            main.post {
                if (!isAdded) return@post
                result.onSuccess {
                    Util.toast(requireContext(), getString(R.string.proot_status_installed, Bootstrap.supportedAbi() ?: "?"))
                    refresh()
                }.onFailure {
                    Util.toast(
                        requireContext(),
                        (it.message ?: it.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    private fun installFromUri(uri: Uri) {
        val dialog = ProgressDialog.show(
            requireContext(),
            getString(R.string.dialog_install_title),
            getString(R.string.installing),
            true,
            false,
        )
        Bootstrap.installFromUriAsync(requireContext().applicationContext, uri, object : BootstrapListener {
            override fun onProgress(message: String, fraction: Int) {
                dialog.setMessage(message)
            }

            override fun onSuccess(manifest: BootstrapManifest) {
                dialog.dismiss()
                if (!isAdded) return
                Util.toast(requireContext(), getString(R.string.install_success))
                refresh()
            }

            override fun onError(error: Exception) {
                dialog.dismiss()
                if (!isAdded) return
                Util.toast(
                    requireContext(),
                    getString(R.string.install_failed, error.message ?: error.javaClass.simpleName),
                )
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
