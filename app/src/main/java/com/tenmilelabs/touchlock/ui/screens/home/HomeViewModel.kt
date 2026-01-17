package com.tenmilelabs.touchlock.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tenmilelabs.touchlock.data.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeLockState: ObserveLockStateUseCase,
    private val startLock: StartLockUseCase,
    private val stopLock: StopLockUseCase,
    private val permissionManager: OverlayPermissionManager
) : ViewModel() {

    val lockState: StateFlow<LockState> =
        observeLockState()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                LockState.Unlocked
            )

    val hasOverlayPermission: Boolean
        get() = permissionManager.hasPermission()

    fun onEnableClicked() {
        startLock()
    }

    fun onDisableClicked() {
        stopLock()
    }
}
