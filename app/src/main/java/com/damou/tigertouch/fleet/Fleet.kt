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
