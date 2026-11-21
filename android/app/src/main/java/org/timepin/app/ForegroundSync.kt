package org.timepin.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.io.IOException

/** Runs on the main looper. The sync itself shares the existing serial network executor. */
internal class ForegroundSync(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val ctx = context.applicationContext
    private val connections = ctx.getSharedPreferences("connections", Context.MODE_PRIVATE)
    private val network = ctx.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var listening = false
    private var running = false
    private var again = false
    private val check = Runnable { runCheck() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = soon()
    }

    fun start() {
        started = true
        connections.registerOnSharedPreferenceChangeListener(this)
        configure()
    }

    fun stop() {
        started = false
        connections.unregisterOnSharedPreferenceChangeListener(this)
        configure()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == "account" || key == null) configure()
    }

    private fun configure() {
        val connected = started && connections.contains("account")
        if (connected && !listening) {
            listening = true
            network.registerDefaultNetworkCallback(callback, main)
            Sync.schedule(ctx)
        } else if (!connected && listening) {
            listening = false
            network.unregisterNetworkCallback(callback)
        }
        main.removeCallbacks(check)
        again = false
        if (connected && network.activeNetwork != null) soon()
    }

    /** Also covers a save that completes just after the activity leaves the foreground. */
    fun edited() {
        if (!connections.contains("account")) return
        if (!listening || network.activeNetwork == null) Sync.retryWhenOnline(ctx) else soon()
    }

    private fun soon() {
        if (!listening) return
        if (running) {
            again = true
            return
        }
        main.removeCallbacks(check)
        main.postDelayed(check, 250)
    }

    private fun runCheck() {
        if (!listening || network.activeNetwork == null) return
        running = true
        Store.net.execute {
            val failure = runCatching {
                Account.read(ctx)?.let { Sync(ctx, Store.get(ctx), it).all(false) }
            }.exceptionOrNull()
            main.post {
                running = false
                if (connections.contains("account") &&
                    (failure is IOException || failure is HttpFailure && failure.status >= 500)) {
                    Sync.retryWhenOnline(ctx)
                }
                if (listening) {
                    if (again) soon() else main.postDelayed(check, 30_000)
                }
                again = false
            }
        }
    }
}
