package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import com.tenmilelabs.touchlock.domain.usecase.ObserveDebugOverlayVisibleUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveUsageTimerUseCase
import com.tenmilelabs.touchlock.domain.usecase.RestoreNotificationUseCase
import com.tenmilelabs.touchlock.domain.usecase.SetDebugOverlayVisibleUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartDelayedLockUseCase
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
    observeLockState: ObserveLockStateUseCase,
    observeUsageTimer: ObserveUsageTimerUseCase,
    observeDebugOverlayVisible: ObserveDebugOverlayVisibleUseCase,
    private val startDelayedLock: StartDelayedLockUseCase,
    private val setDebugOverlayVisible: SetDebugOverlayVisibleUseCase,
    private val restoreNotification: RestoreNotificationUseCase,
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
        observeLockState(),
        _hasOverlayPermission,
        _areNotificationsAvailable,
        observeUsageTimer(),
        observeDebugOverlayVisible()
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
        restoreNotification()
    }

    fun onDelayedLockClicked() {
        startDelayedLock()
    }

    // Debug-only: Toggles overlay visibility for lifecycle debugging
    fun onDebugOverlayVisibleChanged(visible: Boolean) {
        viewModelScope.launch {
            setDebugOverlayVisible(visible)
        }
    }
}
