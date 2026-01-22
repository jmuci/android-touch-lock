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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRequestPermission: () -> Unit
) {
    val lockState by viewModel.lockState.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()

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
        onEnableClicked = viewModel::onEnableClicked,
        onDisableClicked = viewModel::onDisableClicked,
        onDelayedLockClicked = viewModel::onDelayedLockClicked,
        onRequestPermission = onRequestPermission,
        anScreenRotationSettingChanged = viewModel::onScreenRotationSettingChanged
    )
}

@Composable
private fun HomeScreenContent(
    lockState: LockState,
    hasOverlayPermission: Boolean,
    onEnableClicked: () -> Unit,
    onDisableClicked: () -> Unit,
    onDelayedLockClicked: () -> Unit,
    onRequestPermission: () -> Unit,
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
            text = "Touch Lock App",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Screen lock app for safe usage for kids or to prevent accidental touches.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(50.dp))
        if (!hasOverlayPermission) {
            Text(
                text = "Touch Lock needs permission to draw over other apps in order to block touch input.",
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
            return
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
                Modifier.padding(vertical = 16.dp),
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
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Text("Lock in 10s")
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
                contentDescription = "Information",
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Touch Lock is Active!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "You should now switch to the app you want to protect (e.g., YouTube, video call, etc.).",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Touch input is currently disabled. Press Home or Recent Apps to switch apps.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDisableClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text("Unlock Now")
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
                text = "How to Use",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "1. The Touch Lock notification is always available in your notification drawer.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "2. Open your video, call, or any app you want to protect from accidental touches.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "3. Pull down the notification drawer and tap the Touch Lock notification.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "4. One tap locks or unlocks instantly - no expansion needed!",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SettingsCard(modifier: Modifier, onScreenRotationSettingChanged: (OrientationMode) -> Unit) {
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
                contentDescription = "Screen Rotation Settings",
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Screen Rotation",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.size(20.dp))
            Box {
                Button(onClick = { expanded = true }) {
                    Text(text = "Change")
                }

                DropdownMenu(
                    expanded = expanded, //
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestPermission = {},
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestPermission = {},
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
                onEnableClicked = {},
                onDisableClicked = {},
                onDelayedLockClicked = {},
                onRequestPermission = {},
                anScreenRotationSettingChanged = {}
            )
        }
    }
