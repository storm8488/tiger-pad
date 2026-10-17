package com.damou.tigertouch.model

/*
 * One printer, as the dashboard shows it: the firmware's `printer_view_t`
 * (ui/ui_dashboard.h). Every driver fills this and nothing else, so the screens
 * never learn which brand is behind them.
 *
 * The classes are mutable on purpose: a driver keeps one instance, updates the
 * fields a report carries and leaves the others as they were (reports are
 * partial on most printers). [PrinterView.copyDeep] is what leaves the driver.
 */

enum class PvState { IDLE, PRINTING, PAUSED, DONE, ERROR }

/** What a card or the full view can ask of a printer. */
enum class PvControl {
    PAUSE, RESUME, STOP,
    /** Outside the job commands. */
    LIGHT_ON, LIGHT_OFF;

    val isJob: Boolean get() = this == PAUSE || this == RESUME || this == STOP
}

const val COLOR_UNKNOWN = 0x5a5a5a
const val PV_MAX_UNITS = 4
const val PV_MAX_NOZZLES = 4
/** The label of the external spool holder: one slot, shown apart from the boxes. */
const val PV_LABEL_EXT = "Ext."
/** An AMS HT holds one spool: it sits to the right of the external spool, on its row. */
const val PV_LABEL_HT = "AMS HT"

class PvSlot(
    /** A spool is loaded in this slot. */
    var present: Boolean = false,
    /** 0xRRGGBB */
    var color: Int = COLOR_UNKNOWN,
    /** Filament left, 0..100, -1 when unknown. */
    var percent: Int = -1,
    /** "PLA", "PETG"...: what the dashboard shows. */
    var material: String = "",
    /** The slot the printer is feeding from. */
    var selected: Boolean = false,
) {
    fun copy() = PvSlot(present, color, percent, material, selected)
}

/** One unit of filament feeding: a CFS, an AMS, an AMS HT... */
class PvUnit(
    var present: Boolean = false,
    /** "CFS", "CFS 2", "AMS A", "AMS HT" */
    var label: String = "",
    /** 1..4 */
    var slots: Int = 0,
    val slot: Array<PvSlot> = Array(4) { PvSlot() },
) {
    fun copy() = PvUnit(present, label, slots, Array(4) { slot[it].copy() })
    fun clear() {
        present = false; label = ""; slots = 0
        for (i in slot.indices) slot[i] = PvSlot()
    }
}

/** One nozzle of a printer that has several. */
class PvNozzle(
    /** "Left", "Nozzle 2"...: what the tile's caption says. */
    var label: String = "",
    var temp: Float = 0f,
    var target: Float = 0f,
) {
    fun copy() = PvNozzle(label, temp, target)
}

class PrinterView(
    var name: String = "",
    /** The account's brand id: its logo sits beside the name. */
    var brand: String = "",
    var state: PvState = PvState.IDLE,
    /** 0..100 */
    var progress: Int = 0,
    var file: String = "",
    var layer: Int = 0,
    var totalLayer: Int = 0,
    /** Seconds left, 0 when unknown. */
    var leftS: Int = 0,
    var nozzle: Float = 0f,
    var nozzleTarget: Float = 0f,
    /**
     * 0 or 1: one nozzle, read from [nozzle] / [nozzleTarget]. 2..4: the printer
     * says it has that many, and [nozzles] carries them; [nozzle] then holds the
     * active one's reading.
     */
    var nozzleCount: Int = 0,
    /** Index in [nozzles], -1 when the printer does not say. */
    var nozzleActive: Int = -1,
    val nozzles: Array<PvNozzle> = Array(PV_MAX_NOZZLES) { PvNozzle() },
    var bed: Float = 0f,
    var bedTarget: Float = 0f,
    var chamber: Float = 0f,
    var online: Boolean = false,
    /** This connection takes pause / resume / stop. */
    var canControl: Boolean = false,
    /** This connection takes the light on / off command. */
    var canLight: Boolean = false,
    /** The printer says its light is on; false when it does not say. */
    var lightOn: Boolean = false,
    /** Why nothing is shown, when there is a reason. */
    var notice: String = "",
    /** Red when it is a fault, muted when it is just waiting. */
    var noticeError: Boolean = false,
    val unit: Array<PvUnit> = Array(PV_MAX_UNITS) { PvUnit() },
) {
    fun copyDeep() = PrinterView(
        name, brand, state, progress, file, layer, totalLayer, leftS,
        nozzle, nozzleTarget, nozzleCount, nozzleActive,
        Array(PV_MAX_NOZZLES) { nozzles[it].copy() },
        bed, bedTarget, chamber, online, canControl, canLight, lightOn,
        notice, noticeError,
        Array(PV_MAX_UNITS) { unit[it].copy() },
    )

    /** A job is running or paused: the job buttons can act. */
    val jobActive: Boolean get() = state == PvState.PRINTING || state == PvState.PAUSED
}
