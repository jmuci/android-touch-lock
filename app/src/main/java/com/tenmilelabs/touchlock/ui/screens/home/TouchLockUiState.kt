package com.tenmilelabs.touchlock.ui.screens.home

import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.model.UsageTimerState

/**
 * Consolidated UI state for the Home screen.
 * Represents everything the UI needs to render the current screen state.
 *
 * This is the single source of truth for UI rendering, combining:
 * - Domain state (lock, orientation)
 * - Permission/capability checks
 * - Usage tracking
 *
 * @property lockState Current lock state from the service
 * @property orientationMode Current orientation mode setting
 * @property hasOverlayPermission Whether SYSTEM_ALERT_WINDOW permission is granted
 * @property areNotificationsAvailable Whether notifications are enabled and visible
 * @property usageTimer Daily usage timer state
 * @property debugOverlayVisible Debug-only: Whether overlay has visible tint for lifecycle debugging
 */
data class TouchLockUiState(
    val lockState: LockState = LockState.Unlocked,
    val orientationMode: OrientationMode = OrientationMode.FOLLOW_SYSTEM,
    val hasOverlayPermission: Boolean = false,
    val areNotificationsAvailable: Boolean = false,
    val usageTimer: UsageTimerState = UsageTimerState.INITIAL,
    val debugOverlayVisible: Boolean = false
)
