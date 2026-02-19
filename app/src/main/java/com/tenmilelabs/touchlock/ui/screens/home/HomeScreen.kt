package com.tenmilelabs.touchlock.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tenmilelabs.touchlock.BuildConfig
import com.tenmilelabs.touchlock.R
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.model.UsageTimerState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Refresh permission state when app resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    HomeScreenContent(
        lockState = uiState.lockState,
        hasOverlayPermission = uiState.hasOverlayPermission,
        areNotificationsAvailable = uiState.areNotificationsAvailable,
        notificationIssueDescription = viewModel.notificationIssueDescription,
        currentOrientationMode = uiState.orientationMode,
        usageTimer = uiState.usageTimer,
        debugOverlayVisible = uiState.debugOverlayVisible,
        onEnableClicked = viewModel::onEnableClicked,
        onDisableClicked = viewModel::onDisableClicked,
        onDelayedLockClicked = viewModel::onDelayedLockClicked,
        onRequestOverlayPermission = onRequestOverlayPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onScreenRotationSettingChanged = viewModel::onScreenRotationSettingChanged,
        onDebugOverlayVisibleChanged = viewModel::onDebugOverlayVisibleChanged
    )
}

@Composable
internal fun HomeScreenContent(
    lockState: LockState,
    hasOverlayPermission: Boolean,
    areNotificationsAvailable: Boolean,
    notificationIssueDescription: String,
    currentOrientationMode: OrientationMode,
    usageTimer: UsageTimerState,
    debugOverlayVisible: Boolean,
    onEnableClicked: () -> Unit,
    onDisableClicked: () -> Unit,
    onDelayedLockClicked: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onScreenRotationSettingChanged: (OrientationMode) -> Unit,
    onDebugOverlayVisibleChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Add top spacing for better visual balance
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.home_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))
        if (!hasOverlayPermission) {
            Text(
                text = stringResource(R.string.permission_message),
                modifier = Modifier.padding(vertical = 16.dp),
                textAlign = TextAlign.Center
            )

            Button(onClick = onRequestOverlayPermission) {
                Text(stringResource(R.string.grant_permission))
            }
            return
        }

        // Show notification permission warning (if overlay is granted but notifications blocked)
        if (!areNotificationsAvailable) {
            NotificationWarningCard(
                modifier = Modifier.padding(vertical = 16.dp),
                description = notificationIssueDescription,
                onFixClicked = onRequestNotificationPermission
            )
        }

        // Show alert when lock is active
        if (lockState == LockState.Locked) {
            ActiveLockInstructionsCard(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("locked_state_indicator"),
                onDisableClicked = onDisableClicked
            )
        } else {
            HowToUseCard(Modifier.padding(vertical = 16.dp))

            // Adaptive layout: vertical in portrait, horizontal in landscape
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWideLayout = maxWidth >= 600.dp
                
                if (isWideLayout) {
                    // Landscape: Three cards side-by-side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            currentOrientationMode = currentOrientationMode,
                            onScreenRotationSettingChanged = onScreenRotationSettingChanged
                        )
                        UsageTimerCard(
                            modifier = Modifier.weight(1f),
                            usageTimer = usageTimer
                        )
                    }
                } else {
                    // Portrait: Three cards stacked vertically
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingsCard(
                            modifier = Modifier.padding(vertical = 16.dp),
                            currentOrientationMode = currentOrientationMode,
                            onScreenRotationSettingChanged = onScreenRotationSettingChanged
                        )
                        UsageTimerCard(
                            modifier = Modifier.padding(vertical = 16.dp),
                            usageTimer = usageTimer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lock buttons when unlocked
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {

                Button(
                    onClick = onDelayedLockClicked,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("lock_button")
                ) {
                    Text(stringResource(R.string.lock_in_10s))
                }
            }
        }

        // Debug-only: Overlay visibility toggle (for diagnosing overlay lifecycle issues)
        if (BuildConfig.DEBUG) {
            DebugOverlayCard(
                modifier = Modifier.padding(vertical = 16.dp),
                isVisible = debugOverlayVisible,
                onVisibilityChanged = onDebugOverlayVisibleChanged
            )
        }

        // Add bottom spacing for better visual balance
        Spacer(modifier = Modifier.height(32.dp))
    }
}


@Composable
fun ActiveLockInstructionsCard(
    modifier: Modifier,
    onDisableClicked: () -> Unit
) {
    Surface(
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = rememberVectorPainter(Icons.Filled.Info),
                contentDescription = stringResource(R.string.content_description_information),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.active_lock_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.active_lock_switch_instruction),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.active_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

        }
    }
}

@Composable
fun NotificationWarningCard(
    modifier: Modifier,
    description: String,
    onFixClicked: () -> Unit
) {
    Surface(
        shadowElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.notifications_disabled_warning),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onFixClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.enable_notifications))
            }
        }
    }
}

@Composable
fun HowToUseCard(modifier: Modifier) {
    Surface(
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.how_to_use_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.how_to_use_step1),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.how_to_use_step2),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.how_to_use_step3),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.how_to_use_step4),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SettingsCard(
    modifier: Modifier,
    currentOrientationMode: OrientationMode,
    onScreenRotationSettingChanged: (OrientationMode) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberVectorPainter(Icons.Filled.ScreenRotation),
                contentDescription = stringResource(R.string.content_description_screen_rotation),
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.screen_rotation),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(currentOrientationMode.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                Button(onClick = { expanded = true }) {
                    Text(text = stringResource(R.string.change))
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    OrientationMode.entries.forEach { orientation ->
                        DropdownMenuItem(
                            onClick = {
                                onScreenRotationSettingChanged(orientation)
                                expanded = false
                            },
                            text = { Text(text = stringResource(orientation.titleRes)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UsageTimerCard(
    modifier: Modifier,
    usageTimer: UsageTimerState
) {
    Surface(
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatTime(usageTimer.elapsedMillisToday),
                style = MaterialTheme.typography.displayMedium,
                color = if (usageTimer.isRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = stringResource(R.string.time_locked_today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats milliseconds to "Xm Ys" format.
 */
private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}m ${seconds}s"
}

/**
 * Debug-only card for toggling overlay visibility.
 * Used to visually confirm when the overlay is attached (for diagnosing lifecycle issues).
 */
@Composable
fun DebugOverlayCard(
    modifier: Modifier,
    isVisible: Boolean,
    onVisibilityChanged: (Boolean) -> Unit
) {
    Surface(
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🐞 Debug: Overlay Visible",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Shows red tint when overlay is attached",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isVisible,
                onCheckedChange = onVisibilityChanged
            )
        }
    }
}

    @Preview(showBackground = true, name = "Unlocked State")
    @Composable
    private fun HomeScreenUnlockedPreview() {
        MaterialTheme {
            HomeScreenContent(
                lockState = LockState.Unlocked,
                hasOverlayPermission = true,
                areNotificationsAvailable = true,
                notificationIssueDescription = "",
                currentOrientationMode = OrientationMode.FOLLOW_SYSTEM,
                usageTimer = UsageTimerState(elapsedMillisToday = 125000, isRunning = false),
                debugOverlayVisible = false,
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                onScreenRotationSettingChanged = {},
                onDebugOverlayVisibleChanged = {}
            )
        }
    }

    @Preview(showBackground = true, name = "Locked State")
    @Composable
    private fun HomeScreenLockedPreview() {
        MaterialTheme {
            HomeScreenContent(
                lockState = LockState.Locked,
                hasOverlayPermission = true,
                areNotificationsAvailable = true,
                notificationIssueDescription = "",
                currentOrientationMode = OrientationMode.PORTRAIT,
                usageTimer = UsageTimerState(elapsedMillisToday = 450000, isRunning = true),
                debugOverlayVisible = false,
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                onScreenRotationSettingChanged = {},
                onDebugOverlayVisibleChanged = {}
            )
        }
    }

    @Preview(showBackground = true, name = "Active Lock Card")
    @Composable
    private fun ActiveLockInstructionsCardPreview() {
        MaterialTheme {
            ActiveLockInstructionsCard(
                modifier = Modifier.padding(16.dp),
                onDisableClicked = {}
            )
        }
    }

    @Preview(showBackground = true, name = "No Permission")
    @Composable
    private fun HomeScreenNoPermissionPreview() {
        MaterialTheme {
            HomeScreenContent(
                lockState = LockState.Unlocked,
                hasOverlayPermission = false,
                areNotificationsAvailable = true,
                notificationIssueDescription = "",
                currentOrientationMode = OrientationMode.LANDSCAPE,
                usageTimer = UsageTimerState(elapsedMillisToday = 0, isRunning = false),
                debugOverlayVisible = false,
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                onScreenRotationSettingChanged = {},
                onDebugOverlayVisibleChanged = {}
            )
        }
    }

    @Preview(showBackground = true, name = "Notifications Blocked")
    @Composable
    private fun HomeScreenNotificationsBlockedPreview() {
        MaterialTheme {
            HomeScreenContent(
                lockState = LockState.Unlocked,
                hasOverlayPermission = true,
                areNotificationsAvailable = false,
                notificationIssueDescription = "Notifications are disabled for Touch Lock. Enable them to lock/unlock from the notification drawer.",
                currentOrientationMode = OrientationMode.FOLLOW_SYSTEM,
                usageTimer = UsageTimerState(elapsedMillisToday = 0, isRunning = false),
                debugOverlayVisible = false,
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                onScreenRotationSettingChanged = {},
                onDebugOverlayVisibleChanged = {}
            )
        }
    }
