package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.domain.usecase.ObserveUsageTimerUseCase
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val lockRepository: LockRepository,
    private val configRepository: ConfigRepository,
    observeUsageTimer: ObserveUsageTimerUseCase,
    private val overlayPermissionManager: OverlayPermissionManager,
    private val notificationPermissionManager: NotificationPermissionManager
) : ViewModel() {

    // Private permission state flows
    private val _hasOverlayPermission = MutableStateFlow(overlayPermissionManager.hasPermission())
    private val _areNotificationsAvailable = MutableStateFlow(notificationPermissionManager.areNotificationsAvailable())

    /**
     * Single source of truth for UI state.
     * Combines domain state (lock, usage timer) with permission/capability checks.
     */
    val uiState: StateFlow<TouchLockUiState> = combine(
        lockRepository.observeLockState(),
        _hasOverlayPermission,
        _areNotificationsAvailable,
        observeUsageTimer(),
        configRepository.observeDebugOverlayVisible()
    ) { flows ->
        TouchLockUiState(
            lockState = flows[0] as LockState,
            hasOverlayPermission = flows[1] as Boolean,
            areNotificationsAvailable = flows[2] as Boolean,
            usageTimer = flows[3] as UsageTimerState,
            debugOverlayVisible = flows[4] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TouchLockUiState()
    )

    val notificationIssueDescription: String
        get() = notificationPermissionManager.getNotificationIssueDescription()

    /**
     * Checks and updates permission states.
     * Called when the app resumes to detect permission changes.
     * Also restores the notification in case it was dismissed.
     */
    fun refreshPermissionState() {
        _hasOverlayPermission.value = overlayPermissionManager.hasPermission()
        _areNotificationsAvailable.value = notificationPermissionManager.areNotificationsAvailable()
        lockRepository.restoreNotification()
    }

    fun onDelayedLockClicked() {
        lockRepository.startDelayedLock()
    }

    // Debug-only: Toggles overlay visibility for lifecycle debugging
    fun onDebugOverlayVisibleChanged(visible: Boolean) {
        viewModelScope.launch {
            configRepository.setDebugOverlayVisible(visible)
        }
    }
}
