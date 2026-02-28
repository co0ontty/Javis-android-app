package com.screenshotuploader.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object PreferencesManager {
    private val SERVER_URL_KEY = stringPreferencesKey("server_url")
    private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")

    suspend fun saveServerUrl(context: Context, url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = url
        }
    }

    fun getServerUrl(context: Context): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[SERVER_URL_KEY] ?: ""
            }.first()
        }
    }

    suspend fun saveAuthToken(context: Context, token: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_TOKEN_KEY] = token
        }
    }

    fun getAuthToken(context: Context): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[AUTH_TOKEN_KEY] ?: ""
            }.first()
        }
    }
}