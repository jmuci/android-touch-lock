package com.tenmilelabs.touchlock

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tenmilelabs.touchlock.service.LockOverlayService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class TouchLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initTimber()
        startLockService()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    /**
     * Starts the foreground service in idle mode so the notification
     * is always available. The service will not show the overlay until
     * the user explicitly requests it via notification or UI.
     */
    private fun startLockService() {
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_INIT
        }
        ContextCompat.startForegroundService(this, intent)
    }
}