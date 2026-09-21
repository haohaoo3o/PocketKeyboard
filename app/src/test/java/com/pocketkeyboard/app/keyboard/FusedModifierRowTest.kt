package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合页修饰键排键位数据模型的结构校验（Bug 6）：
 * 7 颗键、顺序、锁定语义、平台标签差异、tab / esc 直接发送。
 */
class FusedModifierRowTest {

    @Test
    fun `seven keys in ctrl shift fn modifier tab esc order`() {
        // 顺序与需求一致：ctrl、shift、fn、win/option、alt/cmd、tab、esc。
        // 第 4 / 5 颗随平台换位：Windows = win(GUI) + alt(ALT)，苹果 = option(ALT) + ⌘(GUI)
        assertEquals(
            listOf(
                LockableModifier.CTRL,
                LockableModifier.SHIFT,
                LockableModifier.FN,
                LockableModifier.ALT,
                LockableModifier.GUI,
                null,
                null,
            ),
            fusedModifierRow(DevicePlatform.APPLE).map { it.modifier },
        )
        assertEquals(
            listOf(
                LockableModifier.CTRL,
                LockableModifier.SHIFT,
                LockableModifier.FN,
                LockableModifier.GUI,
                LockableModifier.ALT,
                null,
                null,
            ),
            fusedModifierRow(DevicePlatform.OTHER).map { it.modifier },
        )
        assertEquals(7, fusedModifierRow(DevicePlatform.OTHER).size)
        assertEquals(7, fusedModifierRow(DevicePlatform.APPLE).size)
    }

    @Test
    fun `windows labels are ctrl win alt and apple labels are control option cmd`() {
        val windows = fusedModifierRow(DevicePlatform.OTHER).map { it.spec.labelRes }
        assertEquals(
            listOf(
                R.string.fused_mod_ctrl,
                R.string.fused_mod_shift,
                R.string.fused_mod_fn,
                R.string.fused_mod_win,
                R.string.fused_mod_alt,
                R.string.fused_mod_tab,
                R.string.fused_mod_esc,
            ),
            windows,
        )

        val apple = fusedModifierRow(DevicePlatform.APPLE).map { it.spec.labelRes }
        assertEquals(
            listOf(
                R.string.fused_mod_control,
                R.string.fused_mod_shift,
                R.string.fused_mod_fn,
                R.string.fused_mod_option,
                R.string.fused_mod_cmd,
                R.string.fused_mod_tab,
                R.string.fused_mod_esc,
            ),
            apple,
        )
    }

    @Test
    fun `tab and esc send usages directly without locking`() {
        listOf(DevicePlatform.APPLE, DevicePlatform.OTHER).forEach { platform ->
            val specs = fusedModifierRow(platform)
            val tab = specs[5]
            val esc = specs[6]
            assertNull(tab.modifier)
            assertNull(esc.modifier)
            assertEquals(KeyAction.Usage(HidUsage.KEY_TAB), tab.spec.action)
            assertEquals(KeyAction.Usage(HidUsage.KEY_ESCAPE), esc.spec.action)
        }
    }

    @Test
    fun `lockable modifiers carry modifier usages or fn action`() {
        listOf(DevicePlatform.APPLE, DevicePlatform.OTHER).forEach { platform ->
            fusedModifierRow(platform).take(5).forEach { spec ->
                assertNotNull("锁定修饰键必须有语义动作", spec.spec.action)
                when (spec.modifier) {
                    LockableModifier.CTRL ->
                        assertEquals(KeyAction.Modifier(HidUsage.KEY_LEFT_CONTROL), spec.spec.action)

                    LockableModifier.SHIFT ->
                        assertEquals(KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT), spec.spec.action)

                    LockableModifier.ALT ->
                        assertEquals(KeyAction.Modifier(HidUsage.KEY_LEFT_ALT), spec.spec.action)

                    LockableModifier.GUI ->
                        assertEquals(KeyAction.Modifier(HidUsage.KEY_LEFT_GUI), spec.spec.action)

                    LockableModifier.FN -> assertEquals(KeyAction.Fn, spec.spec.action)
                    null -> error("前 5 颗必须是可锁定修饰键")
                }
            }
        }
    }

    @Test
    fun `every key has an accessibility content description`() {
        listOf(DevicePlatform.APPLE, DevicePlatform.OTHER).forEach { platform ->
            fusedModifierRow(platform).forEach { spec ->
                assertTrue(
                    "修饰键排每颗键都要有无障碍描述",
                    spec.spec.contentDescRes != null,
                )
            }
        }
    }

    @Test
    fun `labels within a row are unique`() {
        listOf(DevicePlatform.APPLE, DevicePlatform.OTHER).forEach { platform ->
            val labels = fusedModifierRow(platform).map { it.spec.labelRes }
            assertEquals(labels.size, labels.toSet().size)
        }
    }
}
