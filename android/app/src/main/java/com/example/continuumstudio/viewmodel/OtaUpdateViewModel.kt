package com.example.continuumstudio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.update.OtaUpdateManager
import com.example.continuumstudio.update.UpdateInfo
import com.example.continuumstudio.update.OtaState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OtaUpdateViewModel(application: Application) : AndroidViewModel(application) {
    
    private val updateManager = OtaUpdateManager(application)
    
    val updateState: StateFlow<OtaState> = updateManager.updateState
    val availableUpdate: StateFlow<UpdateInfo?> = updateManager.availableUpdate
    
    val currentVersion: String = updateManager.getCurrentVersionName()
    val currentVersionCode: Int = updateManager.getCurrentVersionCode()
    
    fun checkForUpdate() {
        viewModelScope.launch {
            updateManager.checkForUpdate()
        }
    }
    
    fun downloadAndInstall() {
        val update = availableUpdate.value ?: return
        viewModelScope.launch {
            updateManager.downloadAndInstall(update)
        }
    }
    
    fun resetState() {
        updateManager.resetState()
    }
}
