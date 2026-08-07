package com.tenmilelabs.touchlock.platform.accessibility

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class AccessibilityServiceHolderTest {

    @Test
    fun `initial state is disconnected with no service`() {
        val holder = AccessibilityServiceHolder()

        assertThat(holder.isConnected.value).isFalse()
        assertThat(holder.currentService()).isNull()
    }

    @Test
    fun `attach marks connected and exposes the service`() {
        val holder = AccessibilityServiceHolder()
        val service = mockk<TouchLockAccessibilityService>(relaxed = true)

        holder.attach(service)

        assertThat(holder.isConnected.value).isTrue()
        assertThat(holder.currentService()).isSameInstanceAs(service)
    }

    @Test
    fun `detach clears connection and service reference`() {
        val holder = AccessibilityServiceHolder()
        holder.attach(mockk<TouchLockAccessibilityService>(relaxed = true))

        holder.detach()

        assertThat(holder.isConnected.value).isFalse()
        assertThat(holder.currentService()).isNull()
    }
}
