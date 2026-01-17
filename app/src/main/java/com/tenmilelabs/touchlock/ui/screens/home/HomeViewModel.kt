package com.tenmilelabs.touchlock.ui.screens.home

import androidx.lifecycle.ViewModel
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val startLock: StartLockUseCase,
    private val stopLock: StopLockUseCase,
    observeLockState: ObserveLockStateUseCase
) : ViewModel() {
/*
    val lockState = observeLockState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LockState.Unlocked)

    fun onStartClicked() = startLock()
    fun onStopClicked() = stopLock()
    */

}
