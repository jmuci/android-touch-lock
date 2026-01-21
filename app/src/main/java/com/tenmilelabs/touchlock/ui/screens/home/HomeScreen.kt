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
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    HomeScreenContent(
        lockState = lockState,
        hasOverlayPermission = viewModel.hasOverlayPermission,
        onEnableClicked = viewModel::onEnableClicked,
        onDisableClicked = viewModel::onDisableClicked,
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
        } else {
            InstructionsCard(Modifier.padding(vertical = 16.dp))
            SettingsCard(
                Modifier.padding(vertical = 16.dp),
                onScreenRotationSettingChanged = anScreenRotationSettingChanged
            )
        }

    }
}


@Composable
fun InstructionsCard(modifier: Modifier) {
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
                text = "1. Open the video, call, or any other app in the foreground.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "2. Open the notification drawer and enable the Touch Lock app on the notification.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "3. To unlock, click on the notification again and disable Touch Lock.",
                style = MaterialTheme.typography.bodyLarge
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
                    OrientationMode.entries.forEach { orientation -> // Get all enum values
                        DropdownMenuItem(
                            onClick = {
                                onScreenRotationSettingChanged(orientation);
                                { expanded = false }
                            },
                            text = { Text(text = orientation.title) } //
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
                onRequestPermission = {},
                anScreenRotationSettingChanged = {}
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
                onRequestPermission = {},
                anScreenRotationSettingChanged = {}
            )
        }
    }
