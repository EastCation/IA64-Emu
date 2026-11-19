package cn.moerain.ia64emu.data

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class VmConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "IA-64 VM",
    var machine: String = "itanium2-vpc",
    var cpu: String = "montecito-9050",
    var memoryMb: Int = 2048,
    var cpus: Int = 1,
    var vga: String = "ati",
    var atiModel: String = "rv100",
    var xres: Int = 1024,
    var yres: Int = 768,
    var disk1: String = "",
    var disk2: String = "",
    var cdrom: String = "",
    var storageBus: String = "auto",
    var nicEnabled: Boolean = true,
    var nicModel: String = "e1000",
    var serialConsole: Boolean = false,
    var exposeVnc: Boolean = false,
    var vncAddress: String = "",
    var extraArgs: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {

    fun machineInfo(): MachineInfo = Machines.find(machine)

    fun summary(): String {
        val mem = if (memoryMb >= 1024) {
            val gb = memoryMb / 1024f
            if (gb == gb.toInt().toFloat()) "${gb.toInt()} GiB" else String.format(Locale.US, "%.1f GiB", gb)
        } else {
            "$memoryMb MiB"
        }
        return "${machineInfo().generation} · $cpu · ${cpus}vCPU · $mem"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("id", id)
        put("name", name)
        put("machine", machine)
        put("cpu", cpu)
        put("memoryMb", memoryMb)
        put("cpus", cpus)
        put("vga", vga)
        put("atiModel", atiModel)
        put("xres", xres)
        put("yres", yres)
        put("disk1", disk1)
        put("disk2", disk2)
        put("cdrom", cdrom)
        put("storageBus", storageBus)
        put("nicEnabled", nicEnabled)
        put("nicModel", nicModel)
        put("serialConsole", serialConsole)
        put("exposeVnc", exposeVnc)
        put("vncAddress", vncAddress)
        put("extraArgs", extraArgs)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        const val FORMAT = 1

        fun newForMachine(machineId: String): VmConfig {
            val machine = Machines.find(machineId)
            return VmConfig(
                name = "IA-64 VM",
                machine = machine.id,
                cpu = machine.defaultCpu,
                memoryMb = machine.defaultRamMb,
                cpus = 1,
            )
        }

        fun fromJson(json: JSONObject): VmConfig = VmConfig(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name").ifBlank { "IA-64 VM" },
            machine = json.optString("machine").ifBlank { "itanium2-vpc" },
            cpu = json.optString("cpu"),
            memoryMb = json.optInt("memoryMb", 2048).coerceIn(256, 65536),
            cpus = json.optInt("cpus", 1).coerceIn(1, 64),
            vga = json.optString("vga").ifBlank { "ati" },
            atiModel = json.optString("atiModel").ifBlank { "rv100" },
            xres = json.optInt("xres", 1024).coerceAtLeast(640),
            yres = json.optInt("yres", 768).coerceAtLeast(480),
            disk1 = json.optString("disk1"),
            disk2 = json.optString("disk2"),
            cdrom = json.optString("cdrom"),
            storageBus = json.optString("storageBus").ifBlank { "auto" },
            nicEnabled = json.optBoolean("nicEnabled", true),
            nicModel = json.optString("nicModel").ifBlank { "e1000" },
            serialConsole = json.optBoolean("serialConsole", false),
            exposeVnc = json.optBoolean("exposeVnc", false),
            vncAddress = json.optString("vncAddress"),
            extraArgs = json.optString("extraArgs"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}
