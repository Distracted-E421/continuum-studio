package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.ui.mode.AppMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.appModeDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_mode")

class AppModeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = application.appModeDataStore
    
    private val _currentMode = MutableStateFlow<AppMode?>(null)
    val currentMode: StateFlow<AppMode?> = _currentMode
    
    private val _hasSelectedMode = MutableStateFlow(false)
    val hasSelectedMode: StateFlow<Boolean> = _hasSelectedMode
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    companion object {
        private val APP_MODE_KEY = stringPreferencesKey("selected_app_mode")
    }
    
    init {
        loadSavedMode()
    }
    
    private fun loadSavedMode() {
        viewModelScope.launch {
            try {
                val savedMode = dataStore.data.map { preferences ->
                    preferences[APP_MODE_KEY]?.let { modeName ->
                        try {
                            AppMode.valueOf(modeName)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }
                }.first()
                
                _currentMode.value = savedMode
                _hasSelectedMode.value = savedMode != null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun selectMode(mode: AppMode) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[APP_MODE_KEY] = mode.name
            }
            _currentMode.value = mode
            _hasSelectedMode.value = true
        }
    }
    
    fun switchMode() {
        val current = _currentMode.value ?: return
        val newMode = when (current) {
            AppMode.CONTINUUM -> AppMode.COGNIX
            AppMode.COGNIX -> AppMode.CONTINUUM
        }
        selectMode(newMode)
    }
    
    fun resetMode() {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences.remove(APP_MODE_KEY)
            }
            _currentMode.value = null
            _hasSelectedMode.value = false
        }
    }
}
