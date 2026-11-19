package cn.moerain.ia64emu.runtime

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class RunStatus {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class RunState(
    val status: RunStatus = RunStatus.IDLE,
    val vmId: String? = null,
    val vmName: String = "",
    val vncPort: Int = 0,
    val message: String = "",
)

object QemuRuntime {

    private val mutableState = MutableLiveData(RunState())
    val state: LiveData<RunState> = mutableState

    fun current(): RunState = mutableState.value ?: RunState()

    fun update(state: RunState) {
        mutableState.value = state
    }

    fun post(state: RunState) {
        mutableState.postValue(state)
    }

    fun isRunning(vmId: String): Boolean {
        val state = current()
        return state.vmId == vmId &&
            (state.status == RunStatus.STARTING ||
                state.status == RunStatus.RUNNING ||
                state.status == RunStatus.STOPPING)
    }

    fun idle(vmId: String? = null, vmName: String = "") {
        update(RunState(RunStatus.IDLE, vmId, vmName))
    }

    fun error(vmId: String?, vmName: String, message: String) {
        update(RunState(RunStatus.ERROR, vmId, vmName, message = message))
    }
}
