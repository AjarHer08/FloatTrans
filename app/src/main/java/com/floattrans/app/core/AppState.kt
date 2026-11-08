package com.floattrans.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable bridge between [com.floattrans.app.CaptureService] and the UI. */
object AppState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _auto = MutableStateFlow(false)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setBusy(value: Boolean) {
        _busy.value = value
    }

    fun setAuto(value: Boolean) {
        _auto.value = value
    }

    fun setStatus(value: String) {
        _status.value = value
    }

    fun setResult(value: String) {
        _result.value = value
    }
}
