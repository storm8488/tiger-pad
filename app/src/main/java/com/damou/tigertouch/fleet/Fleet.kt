package com.damou.tigertouch.fleet

import androidx.compose.runtime.Immutable
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.links.PrinterEntry
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_LABEL_HT
import com.damou.tigertouch.model.PvState
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The fleet as the home page draws it. [PrinterView] is mutable and rebuilt at
 * every refresh, so Compose cannot tell that nothing changed; these are
 * immutable, compared by `equals`, and rounded to what the screen shows (whole
 * degrees, whole minutes), so a card is redrawn only when something on it
 * changes.
 */

@Immutable
data class SlotUi(val present: Boolean, val color: Int, val percent: Int, val material: String, val selected: Boolean)

@Immutable
data class UnitUi(val label: String, val slots: List<SlotUi>) {
    /** The external spool and the AMS HT sit on one row, after the boxes. */
    val single: Boolean get() = label == PV_LABEL_EXT || label.startsWith(PV_LABEL_HT)
}

/** One temperature tile: whole degrees, 0 when unknown. */
@Immutable
data class TempUi(val label: String, val temp: Int, val target: Int, val active: Boolean = false)

/** What the job buttons can do: they recompose only when this changes. */
@Immutable
data class JobUi(val canJob: Boolean, val paused: Boolean, val canLight: Boolean, val lightOn: Boolean)

/** What a printer asks of the people around it, most urgent first: the smart order. */
enum class Attention { ERROR, PAUSED, DONE, LOW_SPOOL, PRINTING, IDLE, OFFLINE }

@Immutable
data class PrinterUi(
    val id: String,
    val name: String,
    val brand: String,
    val cloud: Boolean,
    /** The link has not reported yet. */
    val connecting: Boolean,
    val online: Boolean,
    val state: PvState,
    val progress: Int,
    val file: String,
    val layer: Int,
    val totalLayer: Int,
    /** Minutes left, 0 when unknown. */
    val leftMin: Int,
    /** When the print ends, in minutes since the epoch; 0 when unknown. */
    val endMin: Long,
    val nozzles: List<TempUi>,
    val bed: TempUi,
    val chamber: TempUi,
    val units: List<UnitUi>,
    val canControl: Boolean,
    val canLight: Boolean,
    val lightOn: Boolean,
    val notice: String,
    val noticeError: Boolean,
    /** What is left on the spool at work (the lowest when several are), -1 when unknown. */
    val spool: Int,
) {
    val jobActive: Boolean get() = state == PvState.PRINTING || state == PvState.PAUSED

    val job: JobUi get() = JobUi(online && canControl && jobActive, state == PvState.PAUSED, online && canLight, lightOn)

    val attention: Attention
        get() = when {
            !online -> Attention.OFFLINE
            state == PvState.ERROR -> Attention.ERROR
            state == PvState.PAUSED -> Attention.PAUSED
            state == PvState.DONE -> Attention.DONE
            spool in 0 until LOW_SPOOL -> Attention.LOW_SPOOL
            state == PvState.PRINTING -> Attention.PRINTING
            else -> Attention.IDLE
        }

    companion object {
        /** A spool under this many per cent is nearly empty. */
        const val LOW_SPOOL = 10
    }
}

/**
 * Turns the links' reports into [PrinterUi]. Keeps the temperatures it last
 * showed: a reading moves the shown value only when it is a degree away, so a
 * sensor that wavers by a few tenths around 219.5° does not redraw its card.
 * Used from one coroutine at a time.
 */
class FleetMapper {
    private val shown = HashMap<String, Int>()

    fun map(entries: List<PrinterEntry>, nowMs: Long = System.currentTimeMillis()): List<PrinterUi> {
        val ids = HashSet<String>()
        val out = entries.filter { it.enabled }.map { e ->
            ids += e.printer.id
            one(e, nowMs)
        }
        shown.keys.retainAll { it.substringBefore('|') in ids }
        return out
    }

    private fun temp(id: String, what: String, raw: Float): Int {
        val key = "$id|$what"
        if (raw <= 0f) { shown.remove(key); return 0 }
        val prev = shown[key]
        val t = if (prev != null && abs(raw - prev) < 1f) prev else raw.roundToInt()
        shown[key] = t
        return t
    }

    private fun one(e: PrinterEntry, nowMs: Long): PrinterUi {
        val p = e.printer
        val v = e.view
        if (v == null) {
            return PrinterUi(
                p.id, p.name, p.brand, p.cloud, connecting = true, online = false, state = PvState.IDLE,
                progress = 0, file = "", layer = 0, totalLayer = 0, leftMin = 0, endMin = 0,
                nozzles = listOf(TempUi(Tr.t("Nozzle", "Buse"), 0, 0)), bed = TempUi(Tr.t("Bed", "Plateau"), 0, 0),
                chamber = TempUi(Tr.t("Chamber", "Caisson"), 0, 0), units = emptyList(),
                canControl = false, canLight = false, lightOn = false, notice = "", noticeError = false, spool = -1,
            )
        }
        val nozzles = if (v.nozzleCount >= 2) {
            (0 until v.nozzleCount.coerceAtMost(v.nozzles.size)).map { i ->
                val n = v.nozzles[i]
                TempUi(n.label.ifEmpty { Tr.t("Nozzle ${i + 1}", "Buse ${i + 1}") },
                    temp(p.id, "n$i", n.temp), temp(p.id, "nt$i", n.target), i == v.nozzleActive)
            }
        } else listOf(TempUi(Tr.t("Nozzle", "Buse"), temp(p.id, "n", v.nozzle), temp(p.id, "nt", v.nozzleTarget)))

        val units = v.unit.filter { it.present && it.slots > 0 }.map { u ->
            UnitUi(u.label, (0 until u.slots.coerceIn(0, 4)).map { i ->
                val s = u.slot[i]
                SlotUi(s.present, s.color, s.percent, s.material, s.selected)
            })
        }
        // The spool at work: the selected slots; the lowest of them when there are several.
        val spool = units.flatMap { it.slots }.filter { it.present && it.selected && it.percent >= 0 }
            .minOfOrNull { it.percent } ?: -1
        val busy = v.state == PvState.PRINTING || v.state == PvState.PAUSED
        val leftMin = if (v.leftS > 0) (v.leftS + 59) / 60 else 0
        val endMin = if (busy && v.leftS > 0) (nowMs / 1000 + v.leftS + 30) / 60 else 0L
        return PrinterUi(
            id = p.id, name = v.name, brand = v.brand, cloud = p.cloud, connecting = false, online = v.online,
            state = v.state, progress = v.progress.coerceIn(0, 100), file = v.file,
            layer = v.layer, totalLayer = v.totalLayer, leftMin = leftMin, endMin = endMin,
            nozzles = nozzles,
            bed = TempUi(Tr.t("Bed", "Plateau"), temp(p.id, "b", v.bed), temp(p.id, "bt", v.bedTarget)),
            chamber = TempUi(Tr.t("Chamber", "Caisson"), temp(p.id, "c", v.chamber), 0),
            units = units, canControl = v.canControl, canLight = v.canLight, lightOn = v.lightOn,
            notice = v.notice, noticeError = v.noticeError, spool = spool,
        )
    }
}

/** How the home page orders the printers. */
enum class SortBy {
    /** What asks for someone first: error, pause, finished plate, low spool, then printing by end time, idle, offline. */
    SMART,
    NAME,
    /** The prints that end first; no end last. */
    END,
    BRAND;

    fun label(): String = when (this) {
        SMART -> Tr.t("Needs action", "Action requise")
        NAME -> Tr.t("Name", "Nom")
        END -> Tr.t("End time", "Heure de fin")
        BRAND -> Tr.t("Brand", "Marque")
    }
}

/** One counter of the header's summary, which is also a filter. */
enum class StateFilter {
    PRINTING, PAUSED, ERROR, DONE, LOW_SPOOL, IDLE, OFFLINE;

    fun matches(p: PrinterUi): Boolean = when (this) {
        PRINTING -> p.online && p.state == PvState.PRINTING
        PAUSED -> p.online && p.state == PvState.PAUSED
        ERROR -> p.online && p.state == PvState.ERROR
        DONE -> p.online && p.state == PvState.DONE
        LOW_SPOOL -> p.online && p.spool in 0 until PrinterUi.LOW_SPOOL
        IDLE -> p.online && p.state == PvState.IDLE
        OFFLINE -> !p.online
    }

    fun label(n: Int): String = when (this) {
        PRINTING -> Tr.t("$n printing", "$n en impression")
        PAUSED -> Tr.t("$n paused", "$n en pause")
        ERROR -> Tr.t(if (n > 1) "$n errors" else "$n error", if (n > 1) "$n erreurs" else "$n erreur")
        DONE -> Tr.t("$n done", if (n > 1) "$n terminées" else "$n terminée")
        LOW_SPOOL -> Tr.t("$n low spool", if (n > 1) "$n bobines basses" else "$n bobine basse")
        IDLE -> Tr.t("$n idle", "$n au repos")
        OFFLINE -> Tr.t("$n offline", "$n hors ligne")
    }
}

/** What the home page shows: which printers, in which order. */
@Immutable
data class FleetFilter(val sort: SortBy = SortBy.SMART, val state: StateFilter? = null, val brand: String? = null) {
    val active: Boolean get() = state != null || brand != null
}

/** The printers [f] lets through, in its order. */
fun arrange(all: List<PrinterUi>, f: FleetFilter): List<PrinterUi> {
    val shown = all.filter { (f.state == null || f.state.matches(it)) && (f.brand == null || it.brand == f.brand) }
    val byName = compareBy<PrinterUi, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    // An end time to sort by: a print with none goes after those that have one.
    val end = compareBy<PrinterUi> { if (it.jobActive && it.endMin > 0) it.endMin else Long.MAX_VALUE }
    return shown.sortedWith(
        when (f.sort) {
            SortBy.SMART -> compareBy<PrinterUi> { it.attention.ordinal }.then(end).then(byName)
            SortBy.NAME -> byName
            SortBy.END -> end.then(byName)
            SortBy.BRAND -> compareBy<PrinterUi, String>(String.CASE_INSENSITIVE_ORDER) { com.damou.tigertouch.model.Brands.label(it.brand) }.then(byName)
        },
    )
}

/** The header's summary: a count per state (the zero ones left out), and the next print to end. */
@Immutable
data class Summary(
    val counts: List<Pair<StateFilter, Int>>,
    val nextName: String,
    /** Minutes since the epoch, 0 when nothing prints. */
    val nextEndMin: Long,
    /** The brands of the fleet, for the brand filter. */
    val brands: List<String>,
)

fun summarize(all: List<PrinterUi>): Summary {
    // Idle printers ask nothing of anyone: not counted, the bar keeps its room for what does.
    val counts = StateFilter.entries.filter { it != StateFilter.IDLE }.map { f -> f to all.count { f.matches(it) } }.filter { it.second > 0 }
    val next = all.filter { it.online && it.state == PvState.PRINTING && it.endMin > 0 }.minByOrNull { it.endMin }
    return Summary(counts, next?.name ?: "", next?.endMin ?: 0, all.map { it.brand }.distinct().sorted())
}

/** The home page, ready to draw: the printers shown, in their order, and the summary. */
@Immutable
data class FleetPage(val ids: List<String> = emptyList(), val summary: Summary = Summary(emptyList(), "", 0, emptyList()))

fun page(all: List<PrinterUi>, f: FleetFilter) = FleetPage(arrange(all, f).map { it.id }, summarize(all))
