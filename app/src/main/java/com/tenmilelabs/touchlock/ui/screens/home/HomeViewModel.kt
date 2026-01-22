package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.data.permission.OverlayPermissionManager
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
    private val permissionManager: OverlayPermissionManager
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

    private val _hasOverlayPermission = MutableStateFlow(permissionManager.hasPermission())
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    /**
     * Checks and updates the overlay permission state.
     * Called when the app resumes to detect permission changes.
     * Also restores the notification in case it was dismissed.
     */
    fun refreshPermissionState() {
        _hasOverlayPermission.value = permissionManager.hasPermission()
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
