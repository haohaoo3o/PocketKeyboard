package com.pocketkeyboard.app.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemPairingResponder] 单元测试（Bug 2a）：配对请求按 variant 自动应答的策略。
 *
 * 全部用假 [PairingTargetProvider] / [PairingPinProvider] 注入，不需要 Android 设备，
 * 也不需要真的反射调用系统 API。
 */
class PairingResponderTest {

    private class FakeTarget : PairingTarget {
        var pin: ByteArray? = null
        var confirmation: Boolean? = null
        var pinResult: Boolean = true
        var confirmationResult: Boolean = true

        override fun setPin(pin: ByteArray): Boolean {
            this.pin = pin
            return pinResult
        }

        override fun setPairingConfirmation(confirmed: Boolean): Boolean {
            this.confirmation = confirmed
            return confirmationResult
        }
    }

    private class FakeProvider(override val pairingPinCode: String?) : PairingPinProvider

    private val target = FakeTarget()
    private val provider = object : PairingTargetProvider {
        override fun target(address: String): PairingTarget? =
            if (address == MISSING_ADDRESS) null else target
    }

    private fun responder(pin: String?) =
        SystemPairingResponder(pinCodeProvider = FakeProvider(pin), targetProvider = provider)

    @Test
    fun `passkey entry answers with app pin bytes`() {
        val answer = responder("123456").answer(ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null)

        assertEquals(PairingAnswer.PIN_ANSWERED, answer)
        assertArrayEquals("123456".toByteArray(Charsets.UTF_8), target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `pin entry answers with app pin bytes`() {
        val answer = responder("654321").answer(ADDRESS, HidPairingVariant.PIN, null)

        assertEquals(PairingAnswer.PIN_ANSWERED, answer)
        assertArrayEquals("654321".toByteArray(Charsets.UTF_8), target.pin)
    }

    @Test
    fun `passkey confirmation is auto confirmed`() {
        val answer = responder("123456").answer(ADDRESS, HidPairingVariant.PASSKEY_CONFIRMATION, 246810)

        assertEquals(PairingAnswer.CONFIRMED, answer)
        assertTrue(target.confirmation!!)
        assertNull(target.pin)
    }

    @Test
    fun `consent is auto confirmed`() {
        val answer = responder("123456").answer(ADDRESS, HidPairingVariant.CONSENT, null)

        assertEquals(PairingAnswer.CONFIRMED, answer)
        assertTrue(target.confirmation!!)
    }

    @Test
    fun `missing app pin skips so system dialog handles it`() {
        assertEquals(
            PairingAnswer.SKIPPED,
            responder(null).answer(ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null),
        )
        assertEquals(
            PairingAnswer.SKIPPED,
            responder("   ").answer(ADDRESS, HidPairingVariant.PIN, null),
        )
        assertNull(target.pin)
    }

    @Test
    fun `unknown and oob variants are not touched`() {
        assertEquals(
            PairingAnswer.SKIPPED,
            responder("123456").answer(ADDRESS, HidPairingVariant.OOB, null),
        )
        assertEquals(
            PairingAnswer.SKIPPED,
            responder("123456").answer(ADDRESS, HidPairingVariant.UNKNOWN, null),
        )
        assertNull(target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `refused setPin falls back to system dialog`() {
        target.pinResult = false

        val answer = responder("123456").answer(ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null)

        assertEquals(PairingAnswer.FAILED, answer)
        assertArrayEquals("123456".toByteArray(Charsets.UTF_8), target.pin)
    }

    @Test
    fun `refused confirmation falls back to system dialog`() {
        target.confirmationResult = false

        val answer = responder("123456").answer(ADDRESS, HidPairingVariant.CONSENT, null)

        // 仍然尝试确认（true 已下发），只是系统没接受 → 回退系统对话框
        assertEquals(PairingAnswer.FAILED, answer)
        assertTrue(target.confirmation!!)
    }

    @Test
    fun `unresolvable device fails without touching the system`() {
        val answer = responder("123456").answer(MISSING_ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null)

        assertEquals(PairingAnswer.FAILED, answer)
        assertNull(target.pin)
    }

    @Test
    fun `no pin provider at all skips`() {
        val responder = SystemPairingResponder(pinCodeProvider = null, targetProvider = provider)

        assertEquals(
            PairingAnswer.SKIPPED,
            responder.answer(ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null),
        )
    }

    @Test
    fun `noop responder never answers`() {
        val responder = NoOpPairingResponder()

        assertEquals(
            PairingAnswer.SKIPPED,
            responder.answer(ADDRESS, HidPairingVariant.CONSENT, null),
        )
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val MISSING_ADDRESS = "AA:BB:CC:DD:EE:99"
    }
}
