package cn.moerain.ia64emu

import cn.moerain.ia64emu.bootstrap.BootstrapManifest
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.runtime.QemuArgs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun tokenizerHandlesQuotesAndEscapes() {
        val tokens = QemuArgs.tokenize(
            "-device usb-tablet -drive file='/a b/c.iso',format=raw \"x y\" z\\ q",
        )
        assertEquals(
            listOf(
                "-device",
                "usb-tablet",
                "-drive",
                "file=/a b/c.iso,format=raw",
                "x y",
                "z q",
            ),
            tokens,
        )
    }

    @Test
    fun vmConfigJsonRoundTrip() {
        val original = VmConfig(
            name = "Test VM",
            machine = "hp-zx2000",
            cpu = "mckinley-900",
            memoryMb = 1024,
            cpus = 1,
            disk1 = "/sdcard/disk.qcow2",
            cdrom = "/sdcard/install.iso",
            extraArgs = "-device usb-tablet",
        )
        val restored = VmConfig.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.machine, restored.machine)
        assertEquals(original.memoryMb, restored.memoryMb)
        assertEquals(original.disk1, restored.disk1)
        assertEquals(original.cdrom, restored.cdrom)
        assertEquals(original.extraArgs, restored.extraArgs)
    }

    @Test
    fun manifestParses() {
        val manifest = BootstrapManifest.parse(
            """
            {
              "format": 1,
              "abi": "arm64-v8a",
              "qemuVersion": "11.0.0",
              "builtAt": "2026-01-01T00:00:00Z",
              "entry": "/usr/local/bin/qemu-system-ia64",
              "datadir": "/usr/local/share/qemu",
              "firmware": "/usr/local/share/qemu/ia64-firmware.bin"
            }
            """.trimIndent(),
        )
        assertEquals(1, manifest.format)
        assertEquals("arm64-v8a", manifest.abi)
        assertEquals("11.0.0", manifest.qemuVersion)
        assertTrue(manifest.entry.endsWith("qemu-system-ia64"))
    }
}
