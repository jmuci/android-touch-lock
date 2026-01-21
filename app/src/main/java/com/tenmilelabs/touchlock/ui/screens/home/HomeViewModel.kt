package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.data.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.SetOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeLockState: ObserveLockStateUseCase,
    observeOrientationMode: ObserveOrientationModeUseCase,
    private val startLock: StartLockUseCase,
    private val stopLock: StopLockUseCase,
    private val setOrientationMode: SetOrientationModeUseCase,
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

    val hasOverlayPermission: Boolean
        get() = permissionManager.hasPermission()

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
}
