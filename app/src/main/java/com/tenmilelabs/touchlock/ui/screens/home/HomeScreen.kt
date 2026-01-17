package com.tenmilelabs.touchlock.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.ui.home.HomeViewModel

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
        onRequestPermission = onRequestPermission
    )
}

@Composable
private fun HomeScreenContent(
    lockState: LockState,
    hasOverlayPermission: Boolean,
    onEnableClicked: () -> Unit,
    onDisableClicked: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "Touch Lock",
            style = MaterialTheme.typography.headlineMedium
        )

        if (!hasOverlayPermission) {
            Text(
                text = "Overlay permission is required to disable touch input.",
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
            return
        }

        when (lockState) {
            LockState.Unlocked -> {
                Button(onClick = onEnableClicked) {
                    Text("Enable Touch Lock")
                }
            }
            LockState.Locked -> {
                Button(onClick = onDisableClicked) {
                    Text("Disable Touch Lock")
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
            onRequestPermission = {}
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
            onRequestPermission = {}
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
            onRequestPermission = {}
        )
    }
}
