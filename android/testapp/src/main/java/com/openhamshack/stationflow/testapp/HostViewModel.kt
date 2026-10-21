package com.openhamshack.stationflow.testapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.openhamshack.core.data.DiscoveredBox
import com.openhamshack.core.data.Discovery
import com.openhamshack.core.data.SavedBox
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.core.net.BoxTrust
import com.openhamshack.kind.stationflow.StationFlowKind
import com.openhamshack.kind.stationflow.StationFlowSession

/** Something the picker has to ask before a box opens. */
sealed interface Prompt {
    data class Password(val box: SavedBox, val why: String? = null) : Prompt
    /** [changed]: this box had a different CA trusted before - said loudly, since that is what an impostor looks like. */
    data class Trust(val box: SavedBox, val fingerprint: String, val pem: String, val subject: String, val changed: Boolean) : Prompt
}

/**
 * The host side of the test app: saved boxes, Bonjour, the stand-in sign-in
 * (HostSignIn) and which box is open. Everything StationFlow-specific past
 * "here is a base URL and a client" is the kind's.
 */
class HostViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as TestApp).boxes
    val boxes: StateFlow<List<SavedBox>> = store.boxes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val discovery = Discovery(app, setOf(StationFlowKind.KIND))

    private val _session = MutableStateFlow<StationFlowSession?>(null)
    val session: StateFlow<StationFlowSession?> = _session.asStateFlow()
    private var openBox: SavedBox? = null

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var foreground = false

    // Messages are resolved when they are made, in the locale of that
    // moment: they live a few seconds above the picker, so a language change
    // in between is not worth carrying resource ids through the state.
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun foreground(on: Boolean) {
        foreground = on
        updateDiscovery()
    }

    private fun updateDiscovery() {
        if (foreground && _session.value == null) discovery.start() else discovery.stop()
    }

    /** A typed address: saved, then opened (asking for a password or a certificate on the way if needed). */
    fun add(text: String, done: (String?) -> Unit) {
        val addr = try {
            BoxAddress.parse(text)
        } catch (e: IllegalArgumentException) {
            done(e.message); return
        }
        viewModelScope.launch {
            val box = store.upsert(StationFlowKind.KIND, addr, "")
            done(null)
            open(box)
        }
    }

    /**
     * A box found by Bonjour. A Classic or single-app advert is the app
     * itself; a platform server's advert points at its directory, which
     * says where StationFlow is mounted (the first running one, if several).
     */
    fun openDiscovered(d: DiscoveredBox) {
        viewModelScope.launch {
            if (!d.platform) {
                open(store.upsert(StationFlowKind.KIND, d.address, d.name))
                return@launch
            }
            val app = try {
                HostSignIn.directory(d.address, d.directory!!)
                    .filter { it.kind == StationFlowKind.KIND }
                    .sortedByDescending { it.running }.firstOrNull()
            } catch (e: Exception) {
                _error.value = text(R.string.error_directory, d.name, e.message ?: e.javaClass.simpleName); return@launch
            }
            if (app == null) { _error.value = text(R.string.error_no_icom, d.name); return@launch }
            open(store.upsert(StationFlowKind.KIND, app.base, "${d.name} · ${app.name}"))
        }
    }

    fun forget(box: SavedBox) {
        viewModelScope.launch { store.remove(box.id) }
    }

    fun dismissPrompt() { _prompt.value = null }

    /**
     * Open [box]: ask the box where this device stands, deal with an
     * untrusted certificate or a missing session, then hand the kind a base
     * URL and a client.
     */
    fun open(box: SavedBox) {
        _error.value = null
        viewModelScope.launch {
            _busy.value = text(R.string.connecting, box.title)
            try {
                if (HostSignIn.needsPassword(box.address, box.trustedCa, box.session)) {
                    _prompt.value = Prompt.Password(box, if (box.session != null) text(R.string.sign_in_session_ended) else null)
                    return@launch
                }
                start(box)
            } catch (e: Exception) {
                if (BoxTrust.isUntrusted(e) && box.address.https) askTrust(box)
                else _error.value = text(R.string.error_open, box.address.display, e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = null
            }
        }
    }

    private suspend fun askTrust(box: SavedBox) {
        try {
            val cert = HostSignIn.fetchCa(box.address)
            _prompt.value = Prompt.Trust(box, BoxTrust.fingerprint(cert), BoxTrust.toPem(cert), cert.subjectX500Principal.name,
                changed = box.trustedCa != null)
        } catch (e: Exception) {
            _error.value = text(R.string.error_untrusted_no_ca, box.address.display, e.message ?: e.javaClass.simpleName)
        }
    }

    fun trust(p: Prompt.Trust) {
        _prompt.value = null
        viewModelScope.launch {
            store.setTrustedCa(p.box.id, p.pem)
            open(p.box.copy(trustedCa = p.pem))
        }
    }

    fun signIn(box: SavedBox, password: String, fail: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val cookie = HostSignIn.login(box.address, box.trustedCa, password)
                store.setSession(box.id, cookie)
                _prompt.value = null
                start(box.copy(session = cookie))
            } catch (e: Exception) {
                fail(e.message ?: text(R.string.sign_in_failed))
            }
        }
    }

    private fun start(box: SavedBox) {
        _session.value?.close()
        openBox = box
        val s = StationFlowKind.open(box.address, HostSignIn.clientFor(box.session, box.trustedCa),
            box.name.ifBlank { box.address.display }) { why ->
            // The kind's session stopped working: back to the picker, asking for the password.
            viewModelScope.launch {
                close()
                _prompt.value = Prompt.Password(box.copy(session = null), text(R.string.sign_in_signed_out, why))
            }
        }
        if (BuildConfig.DEBUG) AudioProbe.attach(s.player)
        _session.value = s
        updateDiscovery()
    }

    /** The sign-in the kind asks for from its Tune panel when it is view only. */
    fun signInAgain() {
        val box = openBox ?: return
        close()
        _prompt.value = Prompt.Password(box, null)
    }

    fun close() {
        _session.value?.close()
        _session.value = null
        updateDiscovery()
    }

    override fun onCleared() {
        discovery.stop()
        _session.value?.close()
    }
}
