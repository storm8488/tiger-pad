package com.damou.tigertouch.events

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.fleet.PrinterUi
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What happened to a printer that someone should know about. */
enum class EventKind {
    /** A print finished: the plate waits to be cleared. */
    DONE,
    /** A print stopped in pause (not by this tablet). */
    PAUSED,
    ERROR,
    /** A printer that answered has been silent for [Events.OFFLINE_AFTER_MS]. */
    OFFLINE,
    /** The spool at work fell under 10 %. */
    LOW_SPOOL;

    fun label(): String = when (this) {
        DONE -> Tr.t("Print finished", "Impression terminée")
        PAUSED -> Tr.t("Paused", "En pause")
        ERROR -> Tr.t("Error", "Erreur")
        OFFLINE -> Tr.t("Offline", "Hors ligne")
        LOW_SPOOL -> Tr.t("Spool nearly empty", "Bobine presque vide")
    }
}

@Immutable
data class FleetEvent(
    val atMs: Long,
    val printerId: String,
    val printerName: String,
    val kind: EventKind,
    /** The file, the printer's own words, what is left on the spool... */
    val detail: String,
    /** Someone touched the printer (or the journal) since. */
    val acked: Boolean = false,
)

/** What the settings page decides. */
@Immutable
data class AlertPrefs(
    val sound: Boolean = true,
    /** 0..100 */
    val volume: Int = 70,
    val kinds: Set<EventKind> = EventKind.entries.toSet(),
)

/**
 * Watches the fleet for what changes state, keeps the last [MAX] events in a
 * file (they survive a restart), knows which printers have an event nobody
 * acknowledged yet, beeps and asks the screen to wake.
 */
class Events(context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "events"
        const val MAX = 100
        const val OFFLINE_AFTER_MS = 2 * 60_000L
        /** A pause asked from this tablet is not news. */
        private const val OWN_PAUSE_MS = 20_000L
    }

    private val file = File(context.filesDir, "events.json")
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _events = MutableStateFlow<List<FleetEvent>>(emptyList())
    /** Newest first. */
    val events: StateFlow<List<FleetEvent>> = _events

    private val _alerting = MutableStateFlow<Set<String>>(emptySet())
    /** The printers with an event nobody acknowledged: their tiles blink. */
    val alerting: StateFlow<Set<String>> = _alerting

    private val _wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Something happened: the screen should be bright. */
    val wake: SharedFlow<Unit> = _wake

    private val _prefs = MutableStateFlow(loadPrefs())
    val alertPrefs: StateFlow<AlertPrefs> = _prefs

    /** What was last seen of each printer. */
    private class Seen(
        /** The state the printer was last seen in while it answered. */
        var state: PvState,
        var online: Boolean,
        var file: String,
        /** When it stopped answering, 0 while it answers. */
        var silentSinceMs: Long = 0,
        var offlineSaid: Boolean = false,
        /** A low spool is said once, then again only after a spool of 10 % or more. */
        var spoolArmed: Boolean = true,
    )

    private val seen = HashMap<String, Seen>()
    private val ownPauseAt = HashMap<String, Long>()

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    // ---- What the screens do -------------------------------------------------------

    /** This tablet sent a pause: the pause that follows is not an event. */
    fun ownPause(printerId: String) = synchronized(lock) { ownPauseAt[printerId] = System.currentTimeMillis() }

    /** Someone touched this printer: its events are seen. */
    fun ack(printerId: String) = change { it.map { e -> if (e.printerId == printerId && !e.acked) e.copy(acked = true) else e } }

    fun ackAll() = change { it.map { e -> if (e.acked) e else e.copy(acked = true) } }

    fun setPrefs(p: AlertPrefs) {
        _prefs.value = p
        prefs.edit().putBoolean("sound", p.sound).putInt("volume", p.volume)
            .putStringSet("kinds", p.kinds.map { it.name }.toSet()).apply()
        // An event of a kind no longer followed stops blinking.
        change { it.map { e -> if (!e.acked && e.kind !in p.kinds) e.copy(acked = true) else e } }
    }

    /** A short beep at the set volume, whatever the setting: the settings page tries it with this. */
    fun beep(volume: Int = _prefs.value.volume) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, volume.coerceIn(0, 100))
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        } catch (e: Exception) {
            Log.w(TAG, "no tone: ${e.javaClass.simpleName}")
        }
    }

    // ---- Watching the fleet ----------------------------------------------------------

    /** Called with each new picture of the fleet, off the main thread. */
    fun observe(fleet: List<PrinterUi>, nowMs: Long = System.currentTimeMillis()) {
        val new = ArrayList<FleetEvent>()
        synchronized(lock) {
            val ids = HashSet<String>()
            for (p in fleet) {
                ids += p.id
                if (p.connecting) continue // not reported yet: nothing to compare with
                val s = seen[p.id]
                if (s == null) {
                    // First sight: what it is now is not news.
                    seen[p.id] = Seen(p.state, p.online, p.file, if (p.online) 0 else nowMs, !p.online,
                        spoolArmed = p.spool !in 0 until PrinterUi.LOW_SPOOL)
                    continue
                }
                if (p.online) {
                    val was = s.state
                    val kind = when {
                        p.state == PvState.DONE && (was == PvState.PRINTING || was == PvState.PAUSED) -> EventKind.DONE
                        p.state == PvState.ERROR && was != PvState.ERROR -> EventKind.ERROR
                        p.state == PvState.PAUSED && was == PvState.PRINTING ->
                            if (nowMs - (ownPauseAt[p.id] ?: 0L) < OWN_PAUSE_MS) null else EventKind.PAUSED
                        else -> null
                    }
                    if (kind != null) new += FleetEvent(nowMs, p.id, p.name, kind, detail(kind, p, s))
                    s.state = p.state
                    if (p.state == PvState.PRINTING || p.state == PvState.PAUSED) s.file = p.file
                    s.online = true
                    s.silentSinceMs = 0
                    s.offlineSaid = false
                } else {
                    if (s.online) { s.online = false; s.silentSinceMs = nowMs }
                    if (!s.offlineSaid && s.silentSinceMs > 0 && nowMs - s.silentSinceMs >= OFFLINE_AFTER_MS) {
                        s.offlineSaid = true
                        new += FleetEvent(nowMs, p.id, p.name, EventKind.OFFLINE, p.notice)
                    }
                }
                val low = p.online && p.spool in 0 until PrinterUi.LOW_SPOOL
                if (low && s.spoolArmed) {
                    s.spoolArmed = false
                    new += FleetEvent(nowMs, p.id, p.name, EventKind.LOW_SPOOL, Tr.t("${p.spool} % left", "${p.spool} % restant"))
                } else if (p.online && p.spool >= PrinterUi.LOW_SPOOL) {
                    s.spoolArmed = true
                }
            }
            seen.keys.retainAll(ids)
        }
        for (e in new) Log.i(TAG, "${e.kind} on ${e.printerName}")
        val kinds = _prefs.value.kinds
        val kept = new.filter { it.kind in kinds }
        if (kept.isEmpty()) return
        change { (kept.reversed() + it).take(MAX) }
        _wake.tryEmit(Unit)
        if (_prefs.value.sound) beep()
    }

    private fun detail(kind: EventKind, p: PrinterUi, s: Seen): String = when (kind) {
        EventKind.DONE -> p.file.ifEmpty { s.file }
        EventKind.ERROR -> p.notice
        EventKind.PAUSED -> p.file
        else -> ""
    }

    // ---- Keeping them ------------------------------------------------------------------

    private fun change(f: (List<FleetEvent>) -> List<FleetEvent>) {
        val list = synchronized(lock) {
            val l = f(_events.value)
            _events.value = l
            l
        }
        _alerting.value = list.filter { !it.acked }.map { it.printerId }.toSet()
        scope.launch(Dispatchers.IO) { save(list) }
    }

    private fun save(list: List<FleetEvent>) = synchronized(file) {
        try {
            val a = JSONArray()
            for (e in list) a.put(JSONObject().apply {
                put("at", e.atMs); put("id", e.printerId); put("name", e.printerName)
                put("kind", e.kind.name); put("detail", e.detail); put("acked", e.acked)
            })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun load() {
        val list = try {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { EventKind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                FleetEvent(o.optLong("at"), o.optString("id"), o.optString("name"), kind, o.optString("detail"), o.optBoolean("acked"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "not read: ${e.javaClass.simpleName}")
            return
        }
        // Events seen before the restart come first in time: anything new since is kept on top.
        change { (it + list).take(MAX) }
    }

    private fun loadPrefs(): AlertPrefs {
        val kinds = prefs.getStringSet("kinds", null)
            ?.mapNotNull { runCatching { EventKind.valueOf(it) }.getOrNull() }?.toSet()
            ?: EventKind.entries.toSet()
        return AlertPrefs(prefs.getBoolean("sound", true), prefs.getInt("volume", 70), kinds)
    }
}
