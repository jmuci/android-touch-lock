package com.tenmilelabs.touchlock.data.repository

import android.content.Context
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class LockRepositoryImpl @Inject constructor(
    private val context: Context
) : LockRepository {

    override fun startLock() {
       // LockOverlayService.start(context)
    }

    override fun stopLock() {
      //  LockOverlayService.stop(context)
    }

    override fun observeLockState(): Flow<LockState> {
      //  return LockOverlayService.lockState
        return TODO("Not yet implemented")
    }
}
