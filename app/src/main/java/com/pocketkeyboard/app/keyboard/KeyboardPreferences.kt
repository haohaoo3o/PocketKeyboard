package com.pocketkeyboard.app.keyboard

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * fn + C 循环的键帽颜色：白 / 橙 / 红。
 *
 * 取值沿用苹果 iOS 系统色（橙 #FF9500 / 红 #FF3B30），纯黑背景上对比度充足。
 */
enum class KeyCapColor { WHITE, ORANGE, RED }

/** fn + C 循环的键帽颜色（iOS 系统色），键盘页与小键盘共用。 */
internal val KeyCapOrange = Color(0xFFFF9500)
internal val KeyCapRed = Color(0xFFFF3B30)

/** 偏好档位 → 键帽文字颜色（键盘页与小键盘必须是同一份映射，偏好才对两边都生效）。 */
internal fun KeyCapColor.toKeyCapTextColor(): Color = when (this) {
    KeyCapColor.WHITE -> PureWhite
    KeyCapColor.ORANGE -> KeyCapOrange
    KeyCapColor.RED -> KeyCapRed
}

/** fn + S 循环的键帽字号：小 / 中 / 大。 */
enum class KeyTextSize { SMALL, MEDIUM, LARGE }

/**
 * fn + V 循环的震感强度：关 / 弱 / 中 / 强。
 *
 * 通过 [HapticScale] 单例与触控板反馈强度同步（触控板工程师读同一个单例即可）。
 */
enum class HapticStrength { OFF, WEAK, MEDIUM, STRONG }

/** 键盘页偏好快照（颜色 / 字号 / 震感，全部持久化到 DataStore）。 */
data class KeyboardPreferences(
    val keyCapColor: KeyCapColor = KeyCapColor.WHITE,
    val keyTextSize: KeyTextSize = KeyTextSize.MEDIUM,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM,
)

private val Context.keyboardPrefsStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_preferences",
)

/**
 * 键盘页偏好的本地持久化（DataStore Preferences）。
 *
 * 与配对页的 `DevicePlatformStore` 同一套写法：写枚举名、读时按名回解析，
 * 无法解析的值回落到默认档位，保证老版本数据不会让键盘页崩在启动路上。
 */
class KeyboardPreferencesStore(context: Context) {

    private val dataStore = context.applicationContext.keyboardPrefsStore

    /** 当前偏好；DataStore 首次读取完成前先给默认值。 */
    val preferences: Flow<KeyboardPreferences> = dataStore.data.map { preferences ->
        KeyboardPreferences(
            keyCapColor = preferences[KEY_CAP_COLOR].toEnum(KeyCapColor.entries, KeyCapColor.WHITE),
            keyTextSize = preferences[KEY_TEXT_SIZE].toEnum(KeyTextSize.entries, KeyTextSize.MEDIUM),
            hapticStrength = preferences[HAPTIC_STRENGTH]
                .toEnum(HapticStrength.entries, HapticStrength.MEDIUM),
        )
    }

    /** fn + C：写入键帽字体颜色。 */
    suspend fun setKeyCapColor(color: KeyCapColor) {
        dataStore.edit { it[KEY_CAP_COLOR] = color.name }
    }

    /** fn + S：写入键帽字号。 */
    suspend fun setKeyTextSize(size: KeyTextSize) {
        dataStore.edit { it[KEY_TEXT_SIZE] = size.name }
    }

    /** fn + V：写入震感强度。 */
    suspend fun setHapticStrength(strength: HapticStrength) {
        dataStore.edit { it[HAPTIC_STRENGTH] = strength.name }
    }

    private fun <T : Enum<T>> String?.toEnum(values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == this } ?: fallback

    private companion object {
        val KEY_CAP_COLOR = stringPreferencesKey("key_cap_color")
        val KEY_TEXT_SIZE = stringPreferencesKey("key_text_size")
        val HAPTIC_STRENGTH = stringPreferencesKey("haptic_strength")
    }
}
