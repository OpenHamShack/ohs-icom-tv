package com.openhamshack.stationflow.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openhamshack.core.tv.isTelevision
import com.openhamshack.kind.stationflow.ui.StationFlowTouchScreen
import com.openhamshack.kind.stationflow.ui.StationFlowTvScreen

/**
 * The test host: a box picker standing in for the OpenHamShack app's own, then
 * the StationFlow kind full screen. The kind follows the lifecycle itself
 * (audio and sockets stop when the app is not visible).
 */
class MainActivity : ComponentActivity() {
    private val vm: HostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) HostSignIn.caPortOverride = intent.getIntExtra("caPort", 80)
        // Debug only: `--es open http://10.0.2.2:18320/icom/` opens that box
        // straight away, as typing it in the picker would. Screenshots of the
        // radio display on two emulators and three models meant typing the
        // address dozens of times with a D-pad; this is that, without the
        // typing. A release build ignores it.
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            intent.getStringExtra("open")?.let { vm.add(it) {} }
        }
        val tv = isTelevision(this)
        setContent {
            val session by vm.session.collectAsStateWithLifecycle()
            val s = session
            if (s == null) Picker(vm)
            // A TV gets the D-pad screen; a phone or tablet the same radio by touch.
            else if (tv) StationFlowTvScreen(s, onExit = { vm.close() }, onSignIn = { vm.signInAgain() })
            else StationFlowTouchScreen(s, onExit = { vm.close() }, onSignIn = { vm.signInAgain() })
        }
    }

    override fun onStart() {
        super.onStart()
        vm.foreground(true)
    }

    override fun onStop() {
        vm.foreground(false)
        super.onStop()
    }
}
