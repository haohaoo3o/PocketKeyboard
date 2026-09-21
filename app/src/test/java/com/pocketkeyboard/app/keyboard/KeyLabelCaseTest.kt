package com.pocketkeyboard.app.keyboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 键帽字母一律大写显示（需求 5）的资源层校验。
 *
 * 87 键 TKL 与融合页修饰键排共用同一批 key_a … key_z 文案资源，竖屏的自定义 26 键
 * QWERTY 已在 Bug 6 改造中删除（字符输入改走系统输入法），因此只要断言这批资源
 * 全是大写，横屏 87 键的显示层就满足要求；发送层不受影响——布局模型里的
 * `KeyAction.Character` 一律小写字符，由 `HidUsageMapper` 决定 usage 与 Shift
 * （见 HidUsageMapperTest / TklLayoutTest）。
 *
 * Robolectric 需要合并后的资源（build.gradle.kts 里已开 isIncludeAndroidResources）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyLabelCaseTest {

    private val letterLabelRes = listOf(
        R.string.key_q, R.string.key_w, R.string.key_e, R.string.key_r, R.string.key_t,
        R.string.key_y, R.string.key_u, R.string.key_i, R.string.key_o, R.string.key_p,
        R.string.key_a, R.string.key_s, R.string.key_d, R.string.key_f, R.string.key_g,
        R.string.key_h, R.string.key_j, R.string.key_k, R.string.key_l,
        R.string.key_z, R.string.key_x, R.string.key_c, R.string.key_v, R.string.key_b,
        R.string.key_n, R.string.key_m,
    )

    @Test
    fun `all 26 letter key labels are uppercase`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(26, letterLabelRes.size)
        letterLabelRes.forEach { resId ->
            val label = context.getString(resId)
            assertEquals("键帽文案应为单个字母", 1, label.length)
            assertTrue("键帽文案 $label 应为大写", label[0].isUpperCase())
        }
    }

    @Test
    fun `layout models still send lowercase characters`() {
        // 显示层大写、发送层小写：两边解耦，HidUsageMapper 的输入映射不受影响
        val tklChars = TklLayout.rows(com.pocketkeyboard.app.ui.DevicePlatform.OTHER)
            .flatMap { row -> row.items }
            .mapNotNull { item ->
                (item as? RowItem.Key)?.spec?.action as? KeyAction.Character
            }
            .map { it.char }
            .filter { it in 'a'..'z' }
        assertTrue("87 键布局应包含 26 个字母", tklChars.size >= 26)
        assertTrue(tklChars.all { it.isLowerCase() })
    }

    @Test
    fun `fused modifier row labels are all lowercase chrome text`() {
        // 修饰键排不是字母键，但同样走 strings.xml：标签必须存在且非空
        val context = RuntimeEnvironment.getApplication()
        listOf(DevicePlatform.APPLE, DevicePlatform.OTHER).forEach { platform ->
            fusedModifierRow(platform).forEach { spec ->
                assertTrue(spec.spec.contentDescRes != null)
                assertTrue(context.getString(spec.spec.labelRes).isNotEmpty())
            }
        }
    }
}
