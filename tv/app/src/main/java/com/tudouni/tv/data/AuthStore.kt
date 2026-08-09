package com.tudouni.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tv_settings")

/** 持久化：token / 用户名（DataStore，SharedPreferences 的现代替代）。后端地址固定，不存。 */
class AuthStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
    }

    val token: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }

    val username: Flow<String?> = context.dataStore.data.map { it[Keys.USERNAME] }

    suspend fun saveLogin(token: String, username: String) {
        context.dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.USERNAME] = username
        }
    }

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.USERNAME)
        }
    }
}
