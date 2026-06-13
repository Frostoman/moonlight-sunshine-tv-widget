package com.androidtv.gameswidget.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androidtv.gameswidget.App
import com.androidtv.gameswidget.R
import com.androidtv.gameswidget.databinding.ActivityPairBinding
import com.androidtv.gameswidget.net.PairingManager
import com.androidtv.gameswidget.net.GameStreamHttp
import com.androidtv.gameswidget.sync.SyncManager
import com.androidtv.gameswidget.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Establishes this app's own pairing with the host.
 *
 * Flow: connect -> fetch serverinfo -> show a 4-digit PIN that the user enters in
 * the host's Web UI -> run the handshake -> persist host + pinned server cert -> sync.
 */
class PairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { startPairing() }
    }

    private fun startPairing() {
        val host = binding.hostInput.text.toString().trim()
        if (host.isEmpty()) {
            binding.statusText.text = getString(R.string.pair_hint_host)
            return
        }
        setBusy(true)
        binding.pinText.visibility = View.GONE

        val app = App.from(this)
        lifecycleScope.launch {
            try {
                val http = GameStreamHttp(host, GameStreamHttp.DEFAULT_HTTP_PORT, 0, app.crypto)
                val pm = PairingManager(http, app.crypto)

                val serverInfo = withContext(Dispatchers.IO) { http.serverInfoHttp() }
                val details = http.getComputerDetails(serverInfo)
                val pin = pm.generatePin()

                // Show the PIN; the handshake below blocks until the user enters it.
                binding.pinText.text = pin
                binding.pinText.visibility = View.VISIBLE
                binding.statusText.text = "${getString(R.string.pair_enter_pin)} $pin"

                val state = withContext(Dispatchers.IO) { pm.pair(serverInfo, pin) }

                when (state) {
                    PairingManager.PairState.PAIRED -> {
                        app.hostStore.apply {
                            this.host = host
                            httpPort = GameStreamHttp.DEFAULT_HTTP_PORT
                            httpsPort = http.httpsPort
                            pcName = details.name
                            pcUuid = details.uuid
                            serverCert = pm.pairedCert
                        }
                        binding.statusText.text = getString(R.string.pair_success)
                        SyncWorker.schedule(this@PairActivity)
                        withContext(Dispatchers.IO) { SyncManager(applicationContext).sync() }
                        finish()
                    }
                    PairingManager.PairState.PIN_WRONG ->
                        binding.statusText.text = getString(R.string.pair_wrong_pin)
                    PairingManager.PairState.ALREADY_IN_PROGRESS ->
                        binding.statusText.text = getString(R.string.pair_in_progress)
                    else ->
                        binding.statusText.text = getString(R.string.pair_failed, state.name)
                }
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.pair_failed, e.message ?: getString(R.string.error_network))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !busy
    }
}
