package com.utbionic.verysmartassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Application.dataStore by preferencesDataStore(name = "information")

class Information(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val DEFAULT_MOM_NUMBER = "1234567890"
        private const val DEFAULT_PSW_NUMBER = "1234567890"
        private const val DEFAULT_CONTROLLER_ADDRESS = "very-smart-controller.local"
    }

    private val dataStore = application.dataStore

    private val momNumberKey = stringPreferencesKey("mom_number")
    private val pswNumberKey = stringPreferencesKey("psw_number")
    private val controllerAddressKey = stringPreferencesKey("controller_address")

    var momNumber by mutableStateOf(DEFAULT_MOM_NUMBER)
        private set
    var pswNumber by mutableStateOf(DEFAULT_PSW_NUMBER)
        private set
    var controllerAddress by mutableStateOf(DEFAULT_CONTROLLER_ADDRESS)
        private set
    var isLoaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            dataStore.data.catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }.map { prefs -> prefs.toInfoState() }.collect { state ->
                momNumber = state.momNumber
                pswNumber = state.pswNumber
                controllerAddress = state.controllerAddress
                isLoaded = true
            }
        }
    }

    fun update(newMomNumber: String, newPswNumber: String, newControllerAddress: String) {
        persist {
            it[momNumberKey] = newMomNumber
            it[pswNumberKey] = newPswNumber
            it[controllerAddressKey] = newControllerAddress
        }
    }

    private fun persist(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                block(prefs)
            }
        }
    }

    private fun Preferences.toInfoState(): InfoState {
        return InfoState(
            momNumber = this[momNumberKey] ?: DEFAULT_MOM_NUMBER,
            pswNumber = this[pswNumberKey] ?: DEFAULT_PSW_NUMBER,
            controllerAddress = this[controllerAddressKey] ?: DEFAULT_CONTROLLER_ADDRESS,
        )
    }


    private data class InfoState(
        val momNumber: String,
        val pswNumber: String,
        val controllerAddress: String,
    )
}