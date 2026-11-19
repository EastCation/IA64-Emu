package cn.moerain.ia64emu.data

data class MachineInfo(
    val id: String,
    val label: String,
    val defaultCpu: String,
    val maxCpus: Int,
    val defaultRamMb: Int,
    val generation: String,
)

object Machines {
    val all = listOf(
        MachineInfo("hp-i2000", "HP i2000 (Merced)", "merced-800", 2, 2048, "Merced"),
        MachineInfo("hp-zx2000", "HP zx2000 (McKinley)", "mckinley-900", 1, 1024, "Itanium 2"),
        MachineInfo("hp-zx6000", "HP zx6000 (Madison)", "madison-1500", 2, 2048, "Itanium 2"),
        MachineInfo("hp-rx2660", "HP Integrity rx2660 (Montecito)", "montecito-9010", 8, 8192, "Montecito"),
        MachineInfo("itanium2-vpc", "Itanium 2 虚拟 PC", "montecito-9050", 64, 2048, "Itanium 2"),
        MachineInfo("itanium-vpc", "Itanium 虚拟 PC", "merced-800", 64, 2048, "Merced"),
        MachineInfo("itanium2-vpc-iommu", "Itanium 2 虚拟 PC (VT-d)", "tukwila-9350", 64, 2048, "Tukwila"),
    )

    fun find(id: String): MachineInfo = all.firstOrNull { it.id == id } ?: all[4]

    fun labels(): Array<String> = all.map { it.label }.toTypedArray()

    fun idForLabel(label: String): String = all.firstOrNull { it.label == label }?.id ?: all[4].id
}

data class StorageBusOption(val id: String, val label: String)

object StorageBuses {
    private val auto = StorageBusOption("auto", "Auto")
    private val ide = StorageBusOption("ide", "IDE / SATA")
    private val scsi = StorageBusOption("scsi", "SCSI")

    fun optionsFor(machineId: String): List<StorageBusOption> = when (machineId) {
        "hp-rx2660", "itanium-vpc" -> listOf(auto, scsi)
        "hp-zx2000" -> listOf(auto, ide)
        else -> listOf(auto, ide, scsi)
    }

    fun labelFor(machineId: String, id: String): String =
        optionsFor(machineId).firstOrNull { it.id == id }?.label
            ?: optionsFor(machineId).first().label

    fun idForLabel(machineId: String, label: String): String =
        optionsFor(machineId).firstOrNull { it.label == label }?.id ?: "auto"
}

object Cpus {
    val all = listOf(
        "merced-800",
        "mckinley-900",
        "mckinley-1000",
        "deerfield-1000",
        "madison-1400-1.5m",
        "madison-1400-4m",
        "madison-1500",
        "madison-1600-3m",
        "madison-1600-9m",
        "montecito-9010",
        "montecito-9015",
        "montecito-9020",
        "montecito-9030",
        "montecito-9040",
        "montecito-9050",
        "montvale-9110n",
        "montvale-9120n",
        "montvale-9130m",
        "montvale-9140m",
        "montvale-9140n",
        "montvale-9150m",
        "montvale-9150n",
        "montvale-9152m",
        "tukwila-9310",
        "tukwila-9320",
        "tukwila-9330",
        "tukwila-9340",
        "tukwila-9350",
    )

    val vgaModels = listOf("rv100", "rage128p", "es1000")

    fun forMachine(machineId: String): List<String> = when (machineId) {
        "hp-i2000" -> listOf("merced-800")
        "hp-zx2000" -> listOf("mckinley-900", "madison-1400-1.5m")
        "hp-zx6000" -> listOf("madison-1500")
        "hp-rx2660" -> listOf(
            "montecito-9010",
            "montecito-9020",
            "montecito-9040",
            "montvale-9110n",
            "montvale-9120n",
            "montvale-9140m",
        )
        else -> all
    }
}
