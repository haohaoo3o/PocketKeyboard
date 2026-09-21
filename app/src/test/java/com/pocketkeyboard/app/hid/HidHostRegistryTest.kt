package com.pocketkeyboard.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HidHostRegistry] 与 [ReconnectPolicy] 单元测试。 */
class HidHostRegistryTest {

    private fun entry(
        address: String,
        name: String? = null,
        bond: HidBondState = HidBondState.BONDED,
        state: Int = HidHostRegistry.STATE_DISCONNECTED,
    ) = HidHostRegistry.Entry(address, name, bond, state)

    @Test
    fun `connected hosts keep connection order`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }
        registry.update("B") { entry("B", state = HidHostRegistry.STATE_CONNECTED) }

        assertEquals(listOf("A", "B"), registry.connectedAddresses())
    }

    @Test
    fun `update connection state reports transition to connected`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A") }

        assertFalse(registry.updateConnectionState("A", HidHostRegistry.STATE_CONNECTING))
        assertTrue(registry.updateConnectionState("A", HidHostRegistry.STATE_CONNECTED))
        assertFalse(registry.updateConnectionState("A", HidHostRegistry.STATE_CONNECTED))
    }

    @Test
    fun `disconnect hands active device over to another connected host`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }
        registry.update("B") { entry("B", state = HidHostRegistry.STATE_CONNECTED) }
        registry.setActive("A")

        registry.onDisconnected("A")

        assertEquals("B", registry.activeAddress)
        assertFalse(registry.isConnected("A"))
        assertTrue(registry.isConnected("B"))
    }

    @Test
    fun `set active only switches to known devices`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }

        assertFalse(registry.setActive("UNKNOWN"))
        assertTrue(registry.setActive("A"))
        assertFalse(registry.setActive("A"))
        assertTrue(registry.setActive(null))
        assertNull(registry.activeAddress)
    }

    @Test
    fun `cycle active rotates through connected hosts`() {
        val registry = HidHostRegistry()
        listOf("A", "B", "C").forEach { address ->
            registry.update(address) { entry(address, state = HidHostRegistry.STATE_CONNECTED) }
        }
        registry.setActive("A")

        assertEquals("B", registry.cycleActive())
        assertEquals("C", registry.cycleActive())
        assertEquals("A", registry.cycleActive())
    }

    @Test
    fun `cycle active is a no-op with a single connected host`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }
        registry.setActive("A")

        assertEquals("A", registry.cycleActive())
    }

    @Test
    fun `preferred target falls back to first connected host`() {
        val registry = HidHostRegistry()
        assertNull(registry.preferredTarget())
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }
        assertEquals("A", registry.preferredTarget())
    }

    @Test
    fun `bond removal clears entry and active device`() {
        val registry = HidHostRegistry()
        registry.update("A") { entry("A", state = HidHostRegistry.STATE_CONNECTED) }
        registry.setActive("A")

        registry.onBondRemoved("A")

        assertNull(registry.entry("A"))
        assertNull(registry.activeAddress)
    }

    @Test
    fun `device info falls back to address when name is unknown`() {
        val registry = HidHostRegistry()
        registry.update("AA:BB:CC:DD:EE:FF") { entry("AA:BB:CC:DD:EE:FF") }

        val info = registry.deviceInfo("AA:BB:CC:DD:EE:FF")
        assertNotNull(info)
        assertEquals("AA:BB:CC:DD:EE:FF", info!!.name)
        assertEquals(DevicePlatformHint.UNKNOWN, info.platform)
    }

    @Test
    fun `reconnect policy backs off exponentially and caps out`() {
        assertEquals(0L, ReconnectPolicy.delayFor(0))
        assertEquals(1_000L, ReconnectPolicy.delayFor(1))
        assertEquals(2_000L, ReconnectPolicy.delayFor(2))
        assertEquals(4_000L, ReconnectPolicy.delayFor(3))
        assertEquals(8_000L, ReconnectPolicy.delayFor(4))
        assertEquals(15_000L, ReconnectPolicy.delayFor(20))
        assertTrue(ReconnectPolicy.shouldRetry(1))
        assertFalse(ReconnectPolicy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS))
    }
}
