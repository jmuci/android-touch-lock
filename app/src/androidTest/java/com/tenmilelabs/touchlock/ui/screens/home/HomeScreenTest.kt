package com.tenmilelabs.touchlock.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for HomeScreen.
 *
 * All tests use [HomeScreenContent] in isolation with fake state holders so that
 * no real service, ViewModel, or system permission is required.
 *
 * Test surface covered:
 *  - Lock / unlock state transitions via the lock button
 *  - Overlay permission gate (button hidden, prompt visible when permission missing)
 *  - Notification warning card visibility
 *  - Lock button and locked-state indicator mutual exclusivity
 *  - Usage timer display (format and running vs idle colour are not tested here –
 *    those belong in unit tests; we only verify the card is present)
 *  - Callback wiring (onRequestOverlayPermission, onRequestNotificationPermission)
 */
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Renders [HomeScreenContent] with sensible defaults. Individual tests override
     * only the parameters they care about.
     */
    private fun setContent(
        lockState: LockState = LockState.Unlocked,
        hasOverlayPermission: Boolean = true,
        areNotificationsAvailable: Boolean = true,
        notificationIssueDescription: String = "",
        usageTimer: UsageTimerState = UsageTimerState.INITIAL,
        debugOverlayVisible: Boolean = false,
        onDelayedLockClicked: () -> Unit = {},
        onRequestOverlayPermission: () -> Unit = {},
        onRequestNotificationPermission: () -> Unit = {},
        onDebugOverlayVisibleChanged: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                HomeScreenContent(
                    lockState = lockState,
                    hasOverlayPermission = hasOverlayPermission,
                    areNotificationsAvailable = areNotificationsAvailable,
                    notificationIssueDescription = notificationIssueDescription,
                    usageTimer = usageTimer,
                    debugOverlayVisible = debugOverlayVisible,
                    onDelayedLockClicked = onDelayedLockClicked,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onDebugOverlayVisibleChanged = onDebugOverlayVisibleChanged,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Lock / unlock state
    // ---------------------------------------------------------------------------

    @Test
    fun tappingLockButton_updatesLockedStateInUI() {
        val lockStateFlow = MutableStateFlow<LockState>(LockState.Unlocked)

        composeTestRule.setContent {
            val lockState by lockStateFlow.collectAsState()
            MaterialTheme {
                HomeScreenContent(
                    lockState = lockState,
                    hasOverlayPermission = true,
                    areNotificationsAvailable = true,
                    notificationIssueDescription = "",
                    usageTimer = UsageTimerState.INITIAL,
                    onDelayedLockClicked = { lockStateFlow.value = LockState.Locked },
                    onRequestOverlayPermission = {},
                    onRequestNotificationPermission = {},
                    onDebugOverlayVisibleChanged = {},
                    debugOverlayVisible = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("lock_button")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithTag("locked_state_indicator").assertIsDisplayed()
    }

    @Test
    fun unlockedState_showsLockButtonAndHidesLockedIndicator() {
        setContent(lockState = LockState.Unlocked)

        composeTestRule.onNodeWithTag("lock_button")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("locked_state_indicator").assertDoesNotExist()
    }

    @Test
    fun lockedState_showsLockedIndicatorAndHidesLockButton() {
        setContent(lockState = LockState.Locked)

        composeTestRule.onNodeWithTag("locked_state_indicator").assertIsDisplayed()

        composeTestRule.onNodeWithTag("lock_button").assertDoesNotExist()
    }

    @Test
    fun transitionFromLockedToUnlocked_showsLockButtonAgain() {
        val lockStateFlow = MutableStateFlow<LockState>(LockState.Locked)

        composeTestRule.setContent {
            val lockState by lockStateFlow.collectAsState()
            MaterialTheme {
                HomeScreenContent(
                    lockState = lockState,
                    hasOverlayPermission = true,
                    areNotificationsAvailable = true,
                    notificationIssueDescription = "",
                    usageTimer = UsageTimerState.INITIAL,
                    onDelayedLockClicked = {},
                    onRequestOverlayPermission = {},
                    onRequestNotificationPermission = {},
                    onDebugOverlayVisibleChanged = {},
                    debugOverlayVisible = false,
                )
            }
        }

        // Verify locked state is shown
        composeTestRule.onNodeWithTag("locked_state_indicator").assertIsDisplayed()

        // Simulate unlock
        lockStateFlow.value = LockState.Unlocked

        // Lock button should reappear, locked indicator disappear
        composeTestRule.onNodeWithTag("lock_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("locked_state_indicator").assertDoesNotExist()
    }

    @Test
    fun lockButton_invokesOnDelayedLockClickedCallback() {
        var callbackInvoked = false
        setContent(onDelayedLockClicked = { callbackInvoked = true })

        composeTestRule.onNodeWithTag("lock_button")
            .performScrollTo()
            .performClick()

        assert(callbackInvoked) { "Expected onDelayedLockClicked to be invoked" }
    }

    // ---------------------------------------------------------------------------
    // Overlay permission gate
    // ---------------------------------------------------------------------------

    @Test
    fun missingOverlayPermission_showsGrantPermissionButton() {
        setContent(hasOverlayPermission = false)

        composeTestRule.onNodeWithText("Grant Permission").assertIsDisplayed()
    }

    @Test
    fun missingOverlayPermission_hidesLockButton() {
        setContent(hasOverlayPermission = false)

        composeTestRule.onNodeWithTag("lock_button").assertDoesNotExist()
    }

    @Test
    fun missingOverlayPermission_hidesLockedIndicator() {
        setContent(hasOverlayPermission = false, lockState = LockState.Locked)

        // Permission gate short-circuits the rest of the content
        composeTestRule.onNodeWithTag("locked_state_indicator").assertDoesNotExist()
    }

    @Test
    fun grantPermissionButton_invokesOnRequestOverlayPermissionCallback() {
        var callbackInvoked = false
        setContent(
            hasOverlayPermission = false,
            onRequestOverlayPermission = { callbackInvoked = true },
        )

        composeTestRule.onNodeWithText("Grant Permission").performClick()

        assert(callbackInvoked) { "Expected onRequestOverlayPermission to be invoked" }
    }

    @Test
    fun permissionGranted_hidesGrantPermissionButton() {
        setContent(hasOverlayPermission = true)

        composeTestRule.onNodeWithText("Grant Permission").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Notification warning card
    // ---------------------------------------------------------------------------

    @Test
    fun notificationsUnavailable_showsWarningCard() {
        setContent(
            areNotificationsAvailable = false,
            notificationIssueDescription = "Notifications are disabled.",
        )

        composeTestRule.onNodeWithText("⚠️ Notifications Disabled").assertIsDisplayed()
    }

    @Test
    fun notificationsUnavailable_showsIssueDescription() {
        val description = "Notifications are disabled for Touch Lock."
        setContent(
            areNotificationsAvailable = false,
            notificationIssueDescription = description,
        )

        composeTestRule.onNodeWithText(description).assertIsDisplayed()
    }

    @Test
    fun notificationsUnavailable_enableNotificationsButton_invokesCallback() {
        var callbackInvoked = false
        setContent(
            areNotificationsAvailable = false,
            notificationIssueDescription = "Notifications are disabled.",
            onRequestNotificationPermission = { callbackInvoked = true },
        )

        composeTestRule.onNodeWithText("Enable Notifications").performClick()

        assert(callbackInvoked) { "Expected onRequestNotificationPermission to be invoked" }
    }

    @Test
    fun notificationsAvailable_hidesWarningCard() {
        setContent(areNotificationsAvailable = true)

        composeTestRule.onNodeWithText("⚠️ Notifications Disabled").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Usage timer card
    // ---------------------------------------------------------------------------

    @Test
    fun usageTimerCard_displaysTimeLabelWhenUnlocked() {
        setContent(
            lockState = LockState.Unlocked,
            usageTimer = UsageTimerState(elapsedMillisToday = 0L, isRunning = false),
        )

        composeTestRule.onNodeWithText("Time locked today").assertIsDisplayed()
    }

    @Test
    fun usageTimerCard_formatsElapsedTimeCorrectly() {
        // 125 000 ms = 2m 5s
        setContent(
            lockState = LockState.Unlocked,
            usageTimer = UsageTimerState(elapsedMillisToday = 125_000L, isRunning = false),
        )

        composeTestRule.onNodeWithText("2m 5s").assertIsDisplayed()
    }

    @Test
    fun usageTimerCard_showsZeroTimeOnInitialState() {
        setContent(usageTimer = UsageTimerState.INITIAL)

        composeTestRule.onNodeWithText("0m 0s").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Static content visible in unlocked state
    // ---------------------------------------------------------------------------

    @Test
    fun howToUseCard_isVisibleWhenUnlocked() {
        setContent(lockState = LockState.Unlocked)

        composeTestRule.onNodeWithText("How to Use").assertIsDisplayed()
    }

    @Test
    fun howToUseCard_isHiddenWhenLocked() {
        setContent(lockState = LockState.Locked)

        composeTestRule.onNodeWithText("How to Use").assertDoesNotExist()
    }

    @Test
    fun appTitle_isAlwaysVisible() {
        setContent()

        composeTestRule.onNodeWithText("Touch Lock App").assertIsDisplayed()
    }
}
