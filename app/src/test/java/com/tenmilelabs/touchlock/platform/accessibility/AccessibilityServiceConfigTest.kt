package com.tenmilelabs.touchlock.platform.accessibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the accessibility service's declared capabilities.
 *
 * This is not a style check. Two separate things depend on `canRetrieveWindowContent` staying
 * false:
 *
 *  1. **A published privacy claim.** docs/PLAY_STORE_LAUNCH.md answers the Play Permissions
 *     Declaration with "the service structurally cannot read screen content", and CLAUDE.md makes
 *     the same promise. Flipping this attribute silently turns that answer into a false statement.
 *
 *  2. **Code correctness.** Every window-content API — `getRootInActiveWindow()`, `getWindows()` —
 *     is documented to return null/empty *unless* this capability is declared. Code that calls one
 *     anyway is not defensive, it is dead: an earlier `onServiceConnected()` seeded the foreground
 *     package from `rootInActiveWindow` and could never have worked, which is exactly how the
 *     reconnect bug it was written to fix survived. This test is the standing reminder that those
 *     APIs are unavailable here, so nothing tries again.
 *
 * Reads the checked-in resource file rather than going through `Resources`: app resources aren't
 * available to these unit tests (`isIncludeAndroidResources` is off, and the one Robolectric test
 * in this project only touches framework resources), and the declaration as committed is exactly
 * what the claim above is about.
 */
class AccessibilityServiceConfigTest {

    private val serviceElement: Element by lazy {
        val file = resolveConfigFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
        document.documentElement.also {
            assertThat(it.tagName).isEqualTo("accessibility-service")
        }
    }

    /** Walks up from the working directory so this passes whether Gradle runs from `app/` or root. */
    private fun resolveConfigFile(): File {
        val relative = "src/main/res/xml/accessibility_service_config.xml"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("could not locate $relative from ${File("").absolutePath}")
    }

    private fun attribute(name: String): String? =
        serviceElement.getAttributeNS(ANDROID_NS, name).takeIf { serviceElement.hasAttributeNS(ANDROID_NS, name) }

    @Test
    fun `the service does not request the capability to retrieve window content`() {
        assertThat(attribute("canRetrieveWindowContent")).isEqualTo("false")
    }

    @Test
    fun `the service does not request interactive window retrieval either`() {
        // flagRetrieveInteractiveWindows is the other route to window content, and it is
        // documented to require canRetrieveWindowContent as well — listing it here would be both
        // inert and a contradiction of the same privacy claim.
        assertThat(attribute("accessibilityFlags").orEmpty())
            .doesNotContain("flagRetrieveInteractiveWindows")
    }

    @Test
    fun `the service is not declared as an accessibility tool`() {
        // CLAUDE.md: "Never set isAccessibilityTool=true" — this is a parental-supervision lock,
        // not an assistive technology, and Play treats the two very differently.
        assertThat(attribute("isAccessibilityTool")).isNull()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
