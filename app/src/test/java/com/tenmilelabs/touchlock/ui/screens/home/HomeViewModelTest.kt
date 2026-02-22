package com.tenmilelabs.touchlock.ui.screens.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.usecase.ObserveUsageTimerUseCase
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockPreferences
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockRepository
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var viewModel: HomeViewModel
    private lateinit var lockRepository: FakeLockRepository
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var fakeLockPreferences: FakeLockPreferences
    private lateinit var fakeClock: FakeClock
    private lateinit var observeUsageTimerUseCase: ObserveUsageTimerUseCase
    private lateinit var overlayPermissionManager: OverlayPermissionManager
    private lateinit var notificationPermissionManager: NotificationPermissionManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        lockRepository = FakeLockRepository(autoUpdateState = false)
        configRepository = FakeConfigRepository()
        fakeLockPreferences = FakeLockPreferences()
        fakeClock = FakeClock()
        fakeClock.setDate("2024-01-15")
        overlayPermissionManager = mockk(relaxed = true)
        notificationPermissionManager = mockk(relaxed = true)

        // Set up default mock behavior
        every { overlayPermissionManager.hasPermission() } returns false
        every { notificationPermissionManager.areNotificationsAvailable() } returns false
        every { notificationPermissionManager.getNotificationIssueDescription() } returns ""

        // Create and store the ObserveUsageTimerUseCase to clean it up later
        observeUsageTimerUseCase = ObserveUsageTimerUseCase(
            lockRepository, 
            fakeLockPreferences, 
            fakeClock, 
            testDispatcher
        )

        viewModel = HomeViewModel(
            lockRepository = lockRepository,
            configRepository = configRepository,
            observeUsageTimer = observeUsageTimerUseCase,
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager
        )
    }

    @After
    fun tearDown() {
        observeUsageTimerUseCase.cancelForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.lockState).isEqualTo(LockState.Unlocked)
            assertThat(state.hasOverlayPermission).isFalse()
            assertThat(state.areNotificationsAvailable).isFalse()
        }
    }

    @Test
    fun `uiState lockState reflects changes from repository`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().lockState).isEqualTo(LockState.Unlocked)

            lockRepository.emitLockState(LockState.Locked)
            assertThat(awaitItem().lockState).isEqualTo(LockState.Locked)
            
            lockRepository.emitLockState(LockState.Unlocked)
            assertThat(awaitItem().lockState).isEqualTo(LockState.Unlocked)
            cancelAndIgnoreRemainingEvents()

        }
    }

    @Test
    fun `uiState hasOverlayPermission reflects permission manager state when granted`() = runTest {
        viewModel.uiState.test {
            // Skip initial state
            assertThat(awaitItem().hasOverlayPermission).isFalse()
            
            // Update mock and refresh
            every { overlayPermissionManager.hasPermission() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()
            
            assertThat(awaitItem().hasOverlayPermission).isTrue()
        }
    }

    @Test
    fun `uiState hasOverlayPermission is false when permission not granted`() = runTest {
        viewModel.uiState.test {
            // Default from setup is false
            assertThat(awaitItem().hasOverlayPermission).isFalse()
        }
    }

    @Test
    fun `uiState areNotificationsAvailable reflects permission manager state when available`() = runTest {
        viewModel.uiState.test {
            // Skip initial state
            assertThat(awaitItem().areNotificationsAvailable).isFalse()
            
            // Update mock and refresh
            every { notificationPermissionManager.areNotificationsAvailable() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()
            
            assertThat(awaitItem().areNotificationsAvailable).isTrue()
        }
    }

    @Test
    fun `uiState areNotificationsAvailable is false when not available`() = runTest {
        viewModel.uiState.test {
            // Default from setup is false
            assertThat(awaitItem().areNotificationsAvailable).isFalse()
        }
    }

    @Test
    fun `notificationIssueDescription returns description from permission manager`() {
        val expectedDescription = "Test notification issue"
        every { notificationPermissionManager.getNotificationIssueDescription() } returns expectedDescription

        assertThat(viewModel.notificationIssueDescription).isEqualTo(expectedDescription)
    }

    @Test
    fun `refreshPermissionState updates overlay permission in uiState`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().hasOverlayPermission).isFalse()

            every { overlayPermissionManager.hasPermission() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()

            assertThat(awaitItem().hasOverlayPermission).isTrue()
        }
    }

    @Test
    fun `refreshPermissionState updates notification availability in uiState`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().areNotificationsAvailable).isFalse()

            every { notificationPermissionManager.areNotificationsAvailable() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()

            assertThat(awaitItem().areNotificationsAvailable).isTrue()
        }
    }

    @Test
    fun `refreshPermissionState calls restoreNotification`() {
        assertThat(lockRepository.restoreNotificationCallCount).isEqualTo(0)

        viewModel.refreshPermissionState()

        assertThat(lockRepository.restoreNotificationCallCount).isEqualTo(1)
    }

    @Test
    fun `onDelayedLockClicked calls startDelayedLock on repository`() {
        assertThat(lockRepository.startDelayedLockCallCount).isEqualTo(0)

        viewModel.onDelayedLockClicked()

        assertThat(lockRepository.startDelayedLockCallCount).isEqualTo(1)
    }

    @Test
    fun `multiple permission refreshes update uiState correctly`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().hasOverlayPermission).isFalse()

            // First refresh - grant permission
            every { overlayPermissionManager.hasPermission() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()
            assertThat(awaitItem().hasOverlayPermission).isTrue()

            // Second refresh - revoke permission
            every { overlayPermissionManager.hasPermission() } returns false
            viewModel.refreshPermissionState()
            advanceUntilIdle()
            assertThat(awaitItem().hasOverlayPermission).isFalse()

            // Third refresh - grant again
            every { overlayPermissionManager.hasPermission() } returns true
            viewModel.refreshPermissionState()
            advanceUntilIdle()
            assertThat(awaitItem().hasOverlayPermission).isTrue()
        }
    }

    // Helper function to create a fresh ViewModel with current fake dependencies
    private fun createViewModel() = HomeViewModel(
        lockRepository = lockRepository,
        configRepository = configRepository,
        observeUsageTimer = observeUsageTimerUseCase,
        overlayPermissionManager = overlayPermissionManager,
        notificationPermissionManager = notificationPermissionManager
    )

    // Fake implementations for testing

    private class FakeConfigRepository : ConfigRepository {
        private val debugOverlayVisibleFlow = MutableStateFlow(false)

        override fun observeDebugOverlayVisible(): Flow<Boolean> {
            return debugOverlayVisibleFlow
        }

        override suspend fun setDebugOverlayVisible(visible: Boolean) {
            debugOverlayVisibleFlow.value = visible
        }
    }

}
