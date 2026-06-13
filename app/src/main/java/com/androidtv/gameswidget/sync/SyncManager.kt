package com.androidtv.gameswidget.sync

import android.content.Context
import android.os.Build
import com.androidtv.gameswidget.App
import com.androidtv.gameswidget.data.HostStore
import com.androidtv.gameswidget.net.NvApp
import com.androidtv.gameswidget.net.GameStreamHttp
import com.androidtv.gameswidget.tv.TvChannelPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the game list + box art from the paired host and refreshes both the
 * local cache and the home-screen channel. Safe to call from a coroutine.
 */
class SyncManager(private val context: Context) {

    sealed interface Result {
        data class Success(val games: List<NvApp>) : Result
        data object NotConfigured : Result
        data class Error(val message: String) : Result
    }

    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val app = App.from(context)
        val store = app.hostStore
        if (!store.isConfigured) return@withContext Result.NotConfigured

        val host = store.host ?: return@withContext Result.NotConfigured
        val http = GameStreamHttp(host, store.httpPort, store.httpsPort, app.crypto).apply {
            serverCert = store.serverCert
        }

        try {
            // Refresh server details (port/name may change) over HTTPS.
            android.util.Log.i(TAG, "sync: serverInfoHttps host=$host httpsPort=${store.httpsPort} cert=${store.serverCert != null}")
            val info = http.serverInfoHttps()
            val details = http.getComputerDetails(info)
            android.util.Log.i(TAG, "sync: serverinfo ok name=${details.name} uuid=${details.uuid} httpsPort=${details.httpsPort}")
            store.httpsPort = details.httpsPort
            store.pcName = details.name
            store.pcUuid = details.uuid

            android.util.Log.i(TAG, "sync: fetching applist")
            val raw = http.getAppListRaw()
            android.util.Log.i(TAG, "sync: applist raw length=${raw.length}")
            val games = GameStreamHttp.parseAppList(raw)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
            android.util.Log.i(TAG, "sync: parsed ${games.size} games: ${games.joinToString { it.appName }}")
            store.saveGames(games)

            // Download box art for any game we don't have cached yet.
            for (game in games) {
                val file = store.boxArtFile(game.appId)
                if (file.exists() && file.length() > 0) continue
                runCatching {
                    val bytes = http.getBoxArt(game.appId)
                    if (bytes.isNotEmpty()) file.writeBytes(bytes)
                }
            }

            if (TvChannelPublisher.isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // A channel failure must not break the (working) game list.
                try {
                    android.util.Log.i(TAG, "sync: publishing home-screen channel with ${games.size} games")
                    TvChannelPublisher(context).publish(store, games)
                    android.util.Log.i(TAG, "sync: channel published OK")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "sync: channel publish FAILED", e)
                }
            }

            Result.Success(games)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sync failed", e)
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "MoonlightSync"
    }
}
