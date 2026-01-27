package com.tenmilelabs.touchlock.ui.screens.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.RestoreNotificationUseCase
import com.tenmilelabs.touchlock.domain.usecase.SetOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartDelayedLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
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
    private lateinit var overlayPermissionManager: OverlayPermissionManager
    private lateinit var notificationPermissionManager: NotificationPermissionManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        lockRepository = FakeLockRepository()
        configRepository = FakeConfigRepository()
        overlayPermissionManager = mockk(relaxed = true)
        notificationPermissionManager = mockk(relaxed = true)

        // Set up default mock behavior
        every { overlayPermissionManager.hasPermission() } returns false
        every { notificationPermissionManager.areNotificationsAvailable() } returns false
        every { notificationPermissionManager.getNotificationIssueDescription() } returns ""

        viewModel = HomeViewModel(
            observeLockState = ObserveLockStateUseCase(lockRepository),
            observeOrientationMode = ObserveOrientationModeUseCase(configRepository),
            startLock = StartLockUseCase(lockRepository),
            stopLock = StopLockUseCase(lockRepository),
            startDelayedLock = StartDelayedLockUseCase(lockRepository),
            setOrientationMode = SetOrientationModeUseCase(configRepository),
            restoreNotification = RestoreNotificationUseCase(lockRepository),
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.lockState).isEqualTo(LockState.Unlocked)
            assertThat(state.orientationMode).isEqualTo(OrientationMode.FOLLOW_SYSTEM)
            assertThat(state.hasOverlayPermission).isFalse()
            assertThat(state.areNotificationsAvailable).isFalse()
        }
    }

    @Test
    fun `uiState lockState reflects changes from repository`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().lockState).isEqualTo(LockState.Unlocked)

            lockRepository.emitLockState(LockState.Locked)
            advanceUntilIdle()
            assertThat(awaitItem().lockState).isEqualTo(LockState.Locked)

            lockRepository.emitLockState(LockState.Unlocked)
            advanceUntilIdle()
            assertThat(awaitItem().lockState).isEqualTo(LockState.Unlocked)
        }
    }

    @Test
    fun `uiState orientationMode reflects changes from repository`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().orientationMode).isEqualTo(OrientationMode.FOLLOW_SYSTEM)

            configRepository.emitOrientationMode(OrientationMode.PORTRAIT)
            advanceUntilIdle()
            assertThat(awaitItem().orientationMode).isEqualTo(OrientationMode.PORTRAIT)

            configRepository.emitOrientationMode(OrientationMode.LANDSCAPE)
            advanceUntilIdle()
            assertThat(awaitItem().orientationMode).isEqualTo(OrientationMode.LANDSCAPE)
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
    fun `onEnableClicked calls startLock on repository`() {
        assertThat(lockRepository.startLockCallCount).isEqualTo(0)

        viewModel.onEnableClicked()

        assertThat(lockRepository.startLockCallCount).isEqualTo(1)
    }

    @Test
    fun `onDisableClicked calls stopLock on repository`() {
        assertThat(lockRepository.stopLockCallCount).isEqualTo(0)

        viewModel.onDisableClicked()

        assertThat(lockRepository.stopLockCallCount).isEqualTo(1)
    }

    @Test
    fun `onDelayedLockClicked calls startDelayedLock on repository`() {
        assertThat(lockRepository.startDelayedLockCallCount).isEqualTo(0)

        viewModel.onDelayedLockClicked()

        assertThat(lockRepository.startDelayedLockCallCount).isEqualTo(1)
    }

    @Test
    fun `onScreenRotationSettingChanged calls setOrientationMode with correct mode`() = runTest {
        assertThat(configRepository.setOrientationModeCallCount).isEqualTo(0)
        assertThat(configRepository.lastOrientationMode).isNull()

        viewModel.onScreenRotationSettingChanged(OrientationMode.PORTRAIT)
        advanceUntilIdle()

        assertThat(configRepository.setOrientationModeCallCount).isEqualTo(1)
        assertThat(configRepository.lastOrientationMode).isEqualTo(OrientationMode.PORTRAIT)

        viewModel.onScreenRotationSettingChanged(OrientationMode.LANDSCAPE)
        advanceUntilIdle()

        assertThat(configRepository.setOrientationModeCallCount).isEqualTo(2)
        assertThat(configRepository.lastOrientationMode).isEqualTo(OrientationMode.LANDSCAPE)
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
        observeLockState = ObserveLockStateUseCase(lockRepository),
        observeOrientationMode = ObserveOrientationModeUseCase(configRepository),
        startLock = StartLockUseCase(lockRepository),
        stopLock = StopLockUseCase(lockRepository),
        startDelayedLock = StartDelayedLockUseCase(lockRepository),
        setOrientationMode = SetOrientationModeUseCase(configRepository),
        restoreNotification = RestoreNotificationUseCase(lockRepository),
        overlayPermissionManager = overlayPermissionManager,
        notificationPermissionManager = notificationPermissionManager
    )

    // Fake implementations for testing

    private class FakeLockRepository : LockRepository {
        private val lockStateFlow = MutableStateFlow<LockState>(LockState.Unlocked)
        
        var startLockCallCount = 0
        var stopLockCallCount = 0
        var startDelayedLockCallCount = 0
        var restoreNotificationCallCount = 0

        override fun observeLockState(): Flow<LockState> = lockStateFlow

        override fun startLock() {
            startLockCallCount++
        }

        override fun stopLock() {
            stopLockCallCount++
        }

        override fun startDelayedLock() {
            startDelayedLockCallCount++
        }

        override fun restoreNotification() {
            restoreNotificationCallCount++
        }

        fun emitLockState(state: LockState) {
            lockStateFlow.value = state
        }
    }

    private class FakeConfigRepository : ConfigRepository {
        private val orientationModeFlow = MutableStateFlow(OrientationMode.FOLLOW_SYSTEM)
        
        var setOrientationModeCallCount = 0
        var lastOrientationMode: OrientationMode? = null

        override fun observeOrientationMode(): Flow<OrientationMode> = orientationModeFlow

        override suspend fun setOrientationMode(mode: OrientationMode) {
            setOrientationModeCallCount++
            lastOrientationMode = mode
            orientationModeFlow.value = mode
        }

        fun emitOrientationMode(mode: OrientationMode) {
            orientationModeFlow.value = mode
        }
    }

}
