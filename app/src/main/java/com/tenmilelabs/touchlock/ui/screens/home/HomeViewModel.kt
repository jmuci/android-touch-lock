package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.RestoreNotificationUseCase
import com.tenmilelabs.touchlock.domain.usecase.SetOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartDelayedLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeLockState: ObserveLockStateUseCase,
    observeOrientationMode: ObserveOrientationModeUseCase,
    private val startLock: StartLockUseCase,
    private val stopLock: StopLockUseCase,
    private val startDelayedLock: StartDelayedLockUseCase,
    private val setOrientationMode: SetOrientationModeUseCase,
    private val restoreNotification: RestoreNotificationUseCase,
    private val overlayPermissionManager: OverlayPermissionManager,
    private val notificationPermissionManager: NotificationPermissionManager
) : ViewModel() {

    val lockState: StateFlow<LockState> =
        observeLockState()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                LockState.Unlocked
            )

    val orientationMode: StateFlow<OrientationMode> =
        observeOrientationMode()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                OrientationMode.FOLLOW_SYSTEM
            )

    private val _hasOverlayPermission = MutableStateFlow(overlayPermissionManager.hasPermission())
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    private val _areNotificationsAvailable = MutableStateFlow(notificationPermissionManager.areNotificationsAvailable())
    val areNotificationsAvailable: StateFlow<Boolean> = _areNotificationsAvailable.asStateFlow()

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

    fun onEnableClicked() {
        startLock()
    }

    fun onDisableClicked() {
        stopLock()
    }

    fun onScreenRotationSettingChanged(mode: OrientationMode) {
        viewModelScope.launch {
            setOrientationMode(mode)
        }
    }

    fun onDelayedLockClicked() {
        startDelayedLock()
    }
}
