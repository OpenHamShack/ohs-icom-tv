package com.openhamshack.stationflow.testapp

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.openhamshack.core.data.SavedBoxes

private val Context.dataStore by preferencesDataStore("stationflow-testapp")

class TestApp : Application() {
    val boxes by lazy { SavedBoxes(dataStore) }
}
