package com.pocketkeyboard.app.ui.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketkeyboard.app.ui.DevicePlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设备平台选择的本地持久化（DataStore Preferences）。
 *
 * 首次连接某设备时由配对页弹出平台选择 Dialog，用户的选择按设备地址写入这里；
 * 同一设备后续连接直接读表，不再重复询问。
 */
private val Context.platformDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pairing_device_platforms",
)

/** 按设备地址读写「苹果 / 其他」平台类型。 */
class DevicePlatformStore(context: Context) {

    private val dataStore = context.platformDataStore

    /** 地址 -> 平台；从未询问过的设备不在表内（调用方据此判断是否需要弹窗）。 */
    val platforms: Flow<Map<String, DevicePlatform>> = dataStore.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            val address = key.name.removePrefix(KEY_PREFIX).takeIf { key.name.startsWith(KEY_PREFIX) }
                ?: return@mapNotNull null
            val platform = (value as? String)?.let { raw ->
                DevicePlatform.entries.firstOrNull { it.name == raw }
            } ?: return@mapNotNull null
            address to platform
        }.toMap()
    }

    /** 写入某设备的平台选择（首次连接时询问所得 / 长按重选）。 */
    suspend fun set(address: String, platform: DevicePlatform) {
        dataStore.edit { preferences ->
            preferences[platformKey(address)] = platform.name
        }
    }

    /**
     * 删除某设备的平台记录（Bug 3：侧滑删除设备后清掉，避免再次 bond 同一设备时
     * 直接继承旧平台选择而不再询问）。
     */
    suspend fun remove(address: String) {
        dataStore.edit { preferences ->
            preferences.remove(platformKey(address))
        }
    }

    private fun platformKey(address: String): Preferences.Key<String> =
        stringPreferencesKey(KEY_PREFIX + address)

    private companion object {
        const val KEY_PREFIX = "platform_"
    }
}
