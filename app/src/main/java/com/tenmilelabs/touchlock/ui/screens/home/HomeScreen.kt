package com.tenmilelabs.touchlock.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tenmilelabs.touchlock.R
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val lockState by viewModel.lockState.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val areNotificationsAvailable by viewModel.areNotificationsAvailable.collectAsState()
    val orientationMode by viewModel.orientationMode.collectAsState()

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
        lockState = lockState,
        hasOverlayPermission = hasOverlayPermission,
        areNotificationsAvailable = areNotificationsAvailable,
        notificationIssueDescription = viewModel.notificationIssueDescription,
        currentOrientationMode = orientationMode,
        onEnableClicked = viewModel::onEnableClicked,
        onDisableClicked = viewModel::onDisableClicked,
        onDelayedLockClicked = viewModel::onDelayedLockClicked,
        onRequestOverlayPermission = onRequestOverlayPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        anScreenRotationSettingChanged = viewModel::onScreenRotationSettingChanged
    )
}

@Composable
private fun HomeScreenContent(
    lockState: LockState,
    hasOverlayPermission: Boolean,
    areNotificationsAvailable: Boolean,
    notificationIssueDescription: String,
    currentOrientationMode: OrientationMode,
    onEnableClicked: () -> Unit,
    onDisableClicked: () -> Unit,
    onDelayedLockClicked: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    anScreenRotationSettingChanged: (OrientationMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.home_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(50.dp))
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
                modifier = Modifier.padding(vertical = 16.dp),
                onDisableClicked = onDisableClicked
            )
        } else {
            HowToUseCard(Modifier.padding(vertical = 16.dp))
            SettingsCard(
                modifier = Modifier.padding(vertical = 16.dp),
                currentOrientationMode = currentOrientationMode,
                onScreenRotationSettingChanged = anScreenRotationSettingChanged
            )

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
                ) {
                    Text(stringResource(R.string.lock_in_10s))
                }
            }
        }

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
                Text("Enable Notifications")
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
                    text = currentOrientationMode.title,
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
                            text = { Text(text = orientation.title) }
                        )
                    }
                }
            }
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                anScreenRotationSettingChanged = {}
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                anScreenRotationSettingChanged = {}
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                anScreenRotationSettingChanged = {}
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestOverlayPermission = {},
                onRequestNotificationPermission = {},
                anScreenRotationSettingChanged = {}
            )
        }
    }
