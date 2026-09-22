package com.pocketkeyboard.app.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SystemPairingResponder] 单元测试（配对接管）：配对请求按 variant 自动应答的策略。
 *
 * 全部用假 [PairingTargetProvider] 注入，不需要 Android 设备，也不需要真的反射调用系统 API。
 *
 * 策略要点（与实现同步维护）：
 * - `PIN` → `setPin(App 固定配对码 0000)`：被控设备输入 0000 即完成；
 * - `PASSKEY_ENTRY` → 等用户在 App 输入对端屏幕上的数字，[PairingResponder.submitPasskey] 续答；
 * - `PASSKEY_CONFIRMATION` / `CONSENT` → 自动确认；
 * - `DISPLAY` → 无需应答（本端展示、对端输入）；
 * - `OOB` / `UNKNOWN` → 不干预，走系统配对框。
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

    private val target = FakeTarget()
    private val provider = object : PairingTargetProvider {
        override fun target(address: String): PairingTarget? =
            if (address == MISSING_ADDRESS) null else target
    }

    private fun responder(appPin: String = APP_PAIRING_PIN) =
        SystemPairingResponder(appPinCode = appPin, targetProvider = provider)

    @Test
    fun `pin variant answers with the fixed app pin`() {
        val answer = responder().answer(ADDRESS, HidPairingVariant.PIN, null)

        assertEquals(PairingAnswer.PIN_ANSWERED, answer)
        assertArrayEquals(APP_PAIRING_PIN.toByteArray(Charsets.UTF_8), target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `custom app pin is used verbatim for pin variants`() {
        val answer = responder(appPin = "654321").answer(ADDRESS, HidPairingVariant.PIN, null)

        assertEquals(PairingAnswer.PIN_ANSWERED, answer)
        assertArrayEquals("654321".toByteArray(Charsets.UTF_8), target.pin)
    }

    @Test
    fun `passkey entry waits for the user typed remote key instead of guessing`() {
        // 对端展示的数字硬答必错（App 配对码 ≠ 对端码）：必须等用户转述
        val answer = responder().answer(ADDRESS, HidPairingVariant.PASSKEY_ENTRY, null)

        assertEquals(PairingAnswer.NEED_USER_INPUT, answer)
        assertNull(target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `submitPasskey answers with the user typed digits`() {
        val answer = responder().submitPasskey(ADDRESS, "246810")

        assertEquals(PairingAnswer.PIN_ANSWERED, answer)
        assertArrayEquals("246810".toByteArray(Charsets.UTF_8), target.pin)
    }

    @Test
    fun `empty submit keeps waiting without touching the system`() {
        assertEquals(
            PairingAnswer.NEED_USER_INPUT,
            responder().submitPasskey(ADDRESS, "   "),
        )
        assertNull(target.pin)
    }

    @Test
    fun `passkey confirmation is auto confirmed`() {
        val answer = responder().answer(ADDRESS, HidPairingVariant.PASSKEY_CONFIRMATION, 246810)

        assertEquals(PairingAnswer.CONFIRMED, answer)
        assertEquals(true, target.confirmation)
        assertNull(target.pin)
    }

    @Test
    fun `consent is auto confirmed`() {
        val answer = responder().answer(ADDRESS, HidPairingVariant.CONSENT, null)

        assertEquals(PairingAnswer.CONFIRMED, answer)
        assertEquals(true, target.confirmation)
    }

    @Test
    fun `display variant needs no answer at all`() {
        // 本端展示类：数字由系统生成、用户在被控端输入，误调 setPin 会破坏配对
        val answer = responder().answer(ADDRESS, HidPairingVariant.DISPLAY, 123456)

        assertEquals(PairingAnswer.NO_ANSWER_NEEDED, answer)
        assertNull(target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `unknown and oob variants are not touched`() {
        assertEquals(
            PairingAnswer.SKIPPED,
            responder().answer(ADDRESS, HidPairingVariant.OOB, null),
        )
        assertEquals(
            PairingAnswer.SKIPPED,
            responder().answer(ADDRESS, HidPairingVariant.UNKNOWN, null),
        )
        assertNull(target.pin)
        assertNull(target.confirmation)
    }

    @Test
    fun `refused setPin falls back to system dialog`() {
        target.pinResult = false

        val answer = responder().submitPasskey(ADDRESS, "246810")

        assertEquals(PairingAnswer.FAILED, answer)
        assertArrayEquals("246810".toByteArray(Charsets.UTF_8), target.pin)
    }

    @Test
    fun `refused confirmation falls back to system dialog`() {
        target.confirmationResult = false

        val answer = responder().answer(ADDRESS, HidPairingVariant.CONSENT, null)

        // 仍然尝试确认（true 已下发），只是系统没接受 → 回退系统对话框
        assertEquals(PairingAnswer.FAILED, answer)
        assertEquals(true, target.confirmation)
    }

    @Test
    fun `unresolvable device fails without touching the system`() {
        val answer = responder().answer(MISSING_ADDRESS, HidPairingVariant.PIN, null)

        assertEquals(PairingAnswer.FAILED, answer)
        assertNull(target.pin)
    }

    @Test
    fun `noop responder never answers`() {
        val responder = NoOpPairingResponder()

        assertEquals(
            PairingAnswer.SKIPPED,
            responder.answer(ADDRESS, HidPairingVariant.CONSENT, null),
        )
        assertEquals(
            PairingAnswer.SKIPPED,
            responder.submitPasskey(ADDRESS, "246810"),
        )
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val MISSING_ADDRESS = "AA:BB:CC:DD:EE:99"
    }
}
