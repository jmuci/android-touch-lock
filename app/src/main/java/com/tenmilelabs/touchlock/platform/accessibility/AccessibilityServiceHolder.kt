package com.tenmilelabs.touchlock.platform.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityServiceHolder @Inject constructor() {

    private var service: TouchLockAccessibilityService? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun attach(service: TouchLockAccessibilityService) {
        this.service = service
        _isConnected.value = true
    }

    fun detach() {
        service = null
        _isConnected.value = false
    }

    fun currentService(): TouchLockAccessibilityService? = service
}
