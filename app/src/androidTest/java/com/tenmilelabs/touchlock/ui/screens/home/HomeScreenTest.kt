package com.tenmilelabs.touchlock.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI test for HomeScreen.
 * Verifies that tapping the lock button updates the locked state in the UI.
 */
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingLockButton_updatesLockedStateInUI() {
        // Arrange: Create a fake state holder using StateFlow
        val lockStateFlow = MutableStateFlow<LockState>(LockState.Unlocked)
        
        composeTestRule.setContent {
            val lockState by lockStateFlow.collectAsState()
            
            MaterialTheme {
                HomeScreenContent(
                    lockState = lockState,
                    hasOverlayPermission = true,
                    areNotificationsAvailable = true,
                    notificationIssueDescription = "",
                    currentOrientationMode = OrientationMode.FOLLOW_SYSTEM,
                    usageTimer = UsageTimerState.INITIAL,
                    onDelayedLockClicked = { lockStateFlow.value = LockState.Locked },
                    onRequestOverlayPermission = {},
                    onRequestNotificationPermission = {},
                    onScreenRotationSettingChanged = {},
                    onDebugOverlayVisibleChanged = {},
                    debugOverlayVisible = false
                )
            }
        }

        // Act: Scroll to and tap the lock button
        composeTestRule.onNodeWithTag("lock_button")
            .performScrollTo()
            .performClick()

        // Assert: Verify the UI reflects the locked state
        composeTestRule.onNodeWithTag("locked_state_indicator").assertIsDisplayed()
    }
}
