package com.nik.carradio.backend.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.RadioException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared binding to FYT's com.syu.ms toolkit service. Used by the FYT MCU backend and by the
 * FmNative backend (to switch the MCU audio source to radio).
 */
object SyuConnection {
    @Volatile private var toolkit: Syu.Toolkit? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile var connectedTo: String? = null; private set
    /** Human-readable trail of what was tried during the last connect, for diagnostics. */
    val attempts = mutableListOf<String>()

    fun current(): Syu.Toolkit? = toolkit?.takeIf { it.alive }

    fun installedEndpoints(ctx: Context) = Syu.ENDPOINTS.filter { DeviceInfo.isInstalled(ctx, it) }

    suspend fun connect(ctx: Context, timeoutMs: Long = 4000): Syu.Toolkit {
        current()?.let { return it }
        synchronized(attempts) { attempts.clear() }
        val endpoints = installedEndpoints(ctx)
        if (endpoints.isEmpty()) {
            throw RadioException(
                "FYT system service (com.syu.ms) is not installed",
                "This head unit is not an FYT/Syu platform, so this method cannot work here.",
            )
        }
        for (pkg in endpoints) {
            val intents = listOf(
                Intent(Syu.ACTION_TOOLKIT).setComponent(ComponentName(pkg, Syu.TOOLKIT_SERVICE)),
                Intent(Syu.ACTION_TOOLKIT).setPackage(pkg),
            )
            for (intent in intents) {
                val t = tryBind(ctx.applicationContext, intent, pkg, timeoutMs)
                if (t != null) return t
            }
        }
        throw RadioException(
            "Could not connect to the FYT system service",
            "com.syu.ms is installed but refused the connection. Reboot the unit (ACC off/on) and try again. " +
                "Tried: " + synchronized(attempts) { attempts.joinToString("; ") },
        )
    }

    private suspend fun tryBind(ctx: Context, intent: Intent, pkg: String, timeoutMs: Long): Syu.Toolkit? {
        val label = "${intent.component?.flattenToShortString() ?: (pkg + " by action")}"
        val result = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                if (!result.isCompleted) result.complete(service)
                else if (service != null) {
                    toolkit = Syu.Toolkit(service); AppLog.i("Syu: reconnected to ${name.flattenToShortString()}")
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                AppLog.w("Syu: service disconnected (${name.flattenToShortString()}) – will rebind on next command")
                toolkit = null
            }
            override fun onBindingDied(name: ComponentName) {
                AppLog.w("Syu: binding died (${name.flattenToShortString()})"); toolkit = null
                runCatching { ctx.unbindService(this) }; connection = null
            }
            override fun onNullBinding(name: ComponentName) {
                if (!result.isCompleted) result.complete(null)
            }
        }
        val accepted = try {
            withContext(Dispatchers.Main) { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
        } catch (e: SecurityException) {
            note("$label: SecurityException ${e.message}"); return null
        } catch (e: Exception) {
            note("$label: ${e.javaClass.simpleName} ${e.message}"); return null
        }
        if (!accepted) {
            note("$label: bindService returned false")
            runCatching { ctx.unbindService(conn) }
            return null
        }
        val binder = withTimeoutOrNull(timeoutMs) { result.await() }
        if (binder == null) {
            note("$label: ${if (result.isCompleted) "service returned a null binder" else "no answer within ${timeoutMs}ms"}")
            runCatching { ctx.unbindService(conn) }
            return null
        }
        note("$label: connected")
        connection?.let { old -> runCatching { ctx.unbindService(old) } }
        connection = conn
        connectedTo = label
        return Syu.Toolkit(binder).also { toolkit = it }
    }

    private fun note(s: String) { AppLog.i("Syu bind: $s"); synchronized(attempts) { attempts += s } }

    fun disconnect(ctx: Context) {
        connection?.let { runCatching { ctx.applicationContext.unbindService(it) } }
        connection = null; toolkit = null; connectedTo = null
    }
}
