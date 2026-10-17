package com.damou.tigertouch.links

import android.content.Context
import android.util.Log
import com.damou.tigertouch.drivers.DriverEnv
import com.damou.tigertouch.drivers.Drivers
import com.damou.tigertouch.drivers.PrinterLink
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One printer of the account as the screens see it. */
data class PrinterEntry(
    val printer: AccountPrinter,
    /** A driver can reach it: a switch is only offered then. */
    val supported: Boolean,
    val enabled: Boolean,
    /** The last report; null while the link has not been opened. */
    val view: PrinterView?,
)

/**
 * Which printers stay connected: the firmware's drivers/links.c, without its
 * load budget. A tablet has the memory to keep every switched-on printer linked
 * at once, so a switch is always granted.
 *
 * A switched-on printer keeps its link open and its last report in memory, so
 * showing it is a copy, not a connection. A link is reopened when the account
 * changes what describes the printer (address, code, mode...).
 */
class Links(context: Context, private val env: DriverEnv) {

    private companion object {
        const val TAG = "links"
    }

    private val prefs = context.getSharedPreferences("links", Context.MODE_PRIVATE)
    private val lock = Any()

    private var printers: List<AccountPrinter> = emptyList()
    /** Open links, by account id, with the printer they were opened for. */
    private val open = HashMap<String, Pair<AccountPrinter, PrinterLink>>()
    @Volatile private var networkReady = false

    private val _entries = MutableStateFlow<List<PrinterEntry>>(emptyList())
    val entries: StateFlow<List<PrinterEntry>> = _entries

    /**
     * Switched-off printers are what is stored: a printer the account gains is
     * on by default, which is what a control screen wants.
     */
    private val off: MutableSet<String> = prefs.getStringSet("off", emptySet())!!.toMutableSet()

    fun isEnabled(p: AccountPrinter) = p.id !in off && Drivers.forPrinter(p) != null

    /** After the list was rewritten: let go of the links that no longer match, open the new ones. */
    fun setPrinters(list: List<AccountPrinter>) {
        synchronized(lock) { printers = list }
        sync()
    }

    fun setEnabled(id: String, on: Boolean) {
        synchronized(lock) {
            if (on) off.remove(id) else off.add(id)
            prefs.edit().putStringSet("off", off.toSet()).apply()
        }
        sync()
    }

    /**
     * No link opens before this is true: the network is up and the account has
     * answered. A cloud printer reads its session at start, and a link opened
     * before would sit silent.
     */
    fun setNetworkReady(ready: Boolean) {
        networkReady = ready
        sync()
    }

    fun control(id: String, cmd: PvControl): Boolean {
        val link = synchronized(lock) { open[id]?.second } ?: return false
        return try {
            link.control(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "control failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Closes every link (sign-out). */
    fun closeAll() {
        val all = synchronized(lock) { open.values.map { it.second }.also { open.clear() } }
        all.forEach { runCatching { it.stop() } }
        publish()
    }

    private fun sync() {
        val toStop = ArrayList<PrinterLink>()
        synchronized(lock) {
            val wanted = if (networkReady) printers.filter { isEnabled(it) }.associateBy { it.id } else emptyMap()
            val it = open.entries.iterator()
            while (it.hasNext()) {
                val (id, pair) = it.next()
                val now = wanted[id]
                if (now == null || now != pair.first) { // gone, off, or described differently
                    toStop += pair.second
                    it.remove()
                }
            }
            for ((id, p) in wanted) {
                if (id in open) continue
                val drv = Drivers.forPrinter(p) ?: continue
                try {
                    open[id] = p to drv.start(p, env)
                    Log.i(TAG, "${p.brand}: link opened")
                } catch (e: Exception) {
                    Log.w(TAG, "${p.brand}: link failed to start: ${e.javaClass.simpleName}")
                }
            }
        }
        toStop.forEach { runCatching { it.stop() } }
        publish()
    }

    private fun publish() {
        val list = synchronized(lock) {
            printers.map { p ->
                val view = open[p.id]?.second?.let { l ->
                    try {
                        l.snapshot().also { v ->
                            v.name = p.name // the account's name, whatever the driver found
                            v.brand = p.brand
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                PrinterEntry(p, Drivers.forPrinter(p) != null, isEnabled(p), view)
            }
        }
        _entries.value = list
    }

    /** Refreshes every report once a second, as the firmware's feed task does. */
    fun run(scope: CoroutineScope) = scope.launch(Dispatchers.Default) {
        while (isActive) {
            publish()
            delay(1000)
        }
    }
}
