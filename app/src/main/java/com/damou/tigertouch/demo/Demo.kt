package com.damou.tigertouch.demo

import android.content.Context
import android.os.SystemClock
import com.damou.tigertouch.drivers.DriverEnv
import com.damou.tigertouch.drivers.DrvKind
import com.damou.tigertouch.drivers.PrinterDriver
import com.damou.tigertouch.drivers.PrinterLink
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_LABEL_HT
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.model.PvUnit
import java.util.Random
import kotlin.math.sin

/*
 * Demo mode (Settings > Demo): the firmware's demo_mode.c, for a showroom, a
 * fair, screenshots, and for measuring the screens with a fleet the owner does
 * not have. While it is on, the printers on the screen are made-up ones, and
 * nothing is real: no printer of the account is connected, no command leaves
 * the device, and the account is told (`demo` in the presence document) with no
 * printer listed.
 *
 * Unlike the firmware's ten, the count goes from 1 to 20, and the made-up
 * printers can all print at once (the worst case the screens are measured on)
 * or show a mix of states: pause, error, done, offline, a spool running out.
 */

/** What the settings page stores. */
data class DemoConfig(
    val on: Boolean = false,
    /** 1..[Demo.MAX] */
    val count: Int = 15,
    /** Every printer prints; off: a mix of states. */
    val allPrinting: Boolean = true,
)

object Demo {
    const val MAX = 20
    const val BRAND = "demo"

    fun load(ctx: Context): DemoConfig {
        val p = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE)
        return DemoConfig(
            on = p.getBoolean("on", false),
            count = p.getInt("count", 15).coerceIn(1, MAX),
            allPrinting = p.getBoolean("all_printing", true),
        )
    }

    fun save(ctx: Context, c: DemoConfig) {
        ctx.getSharedPreferences("demo", Context.MODE_PRIVATE).edit()
            .putBoolean("on", c.on).putInt("count", c.count).putBoolean("all_printing", c.allPrinting).apply()
    }

    /** The made-up printers, as the account would list them. */
    fun printers(c: DemoConfig): List<AccountPrinter> = (0 until c.count.coerceIn(1, MAX)).map { i ->
        val d = TABLE[i]
        AccountPrinter(brand = d.brand, id = "demo-$i-${if (c.allPrinting) "p" else "m"}", name = d.name, demo = true)
    }

    /** Every live demo printer, for [incident]. */
    private val live = HashSet<DemoLink>()

    /**
     * Something goes wrong somewhere, for trying the alerts: a printing printer
     * stops on an error, and the one closest to its end finishes at once.
     */
    fun incident() {
        val printing = synchronized(live) { live.filter { it.isPrinting() } }
        if (printing.isEmpty()) return
        val err = printing.random()
        err.fail()
        (printing - err).maxByOrNull { it.progressNow() }?.finishNow()
    }

    internal fun register(l: DemoLink, on: Boolean) {
        synchronized(live) { if (on) live += l else live -= l }
    }

    val DRIVER: PrinterDriver = object : PrinterDriver {
        override val brand = BRAND
        override fun support(p: AccountPrinter) = if (p.demo) DrvKind.LAN else DrvKind.NONE
        override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink {
            val k = p.id.split('-').getOrNull(1)?.toIntOrNull()?.coerceIn(0, MAX - 1) ?: 0
            return DemoLink(k, p.id.endsWith("-p")).also { register(it, true) }
        }
    }
}

internal enum class U { NONE, AMS, CFS, CANVAS, ACE, STATION, TOOLS, AFC, AMS_HT }

/** How a printer behaves in the mixed demo. */
internal enum class Mix {
    PRINT,
    /** Starts paused. */
    PAUSE,
    IDLE,
    /** Prints, then stops on a filament runout after two minutes. */
    FAULT,
    /** Starts finished: its plate waits to be cleared. */
    DONE,
    /** Answers for a minute and a half, then falls silent for four. */
    FLAKY,
    /** Its spool is nearly empty, and drops under 10 % while it prints. */
    LOW_SPOOL,
}

internal class DemoPrinter(
    val brand: String, val name: String, val nozzles: Int, val unit: U, val light: Boolean,
    val chamber: Boolean, val progress: Int, val file: String, val mix: Mix,
)

/** Every brand, every kind of filament unit. The first ten are the firmware's. */
internal val TABLE = listOf(
    DemoPrinter("bambulab", "X1 Carbon", 1, U.AMS, true, true, 42, "Benchy", Mix.PRINT),
    DemoPrinter("creality", "K2 Plus", 1, U.CFS, true, true, 67, "Gridfinity bin", Mix.PRINT),
    DemoPrinter("elegoo", "Centauri Carbon 2", 1, U.CANVAS, true, true, 18, "Spool holder", Mix.PRINT),
    DemoPrinter("anycubic", "Kobra S1", 1, U.ACE, true, false, 88, "Phone stand", Mix.LOW_SPOOL),
    DemoPrinter("snapmaker", "U1", 4, U.TOOLS, true, false, 55, "Four-colour vase", Mix.PRINT),
    DemoPrinter("flashforge", "AD5X", 1, U.STATION, true, false, 31, "Cable clips", Mix.PAUSE),
    DemoPrinter("prusa", "Core One", 1, U.NONE, false, true, 74, "Lamp shade", Mix.PRINT),
    DemoPrinter("klipper", "Voron 2.4", 1, U.AFC, true, true, 9, "Gear set", Mix.PRINT),
    DemoPrinter("bambulab", "P2S", 1, U.AMS, true, true, 95, "Planter", Mix.PRINT),
    DemoPrinter("octoprint", "Ender-3 S1", 1, U.NONE, false, false, 0, "", Mix.IDLE),
    DemoPrinter("creality", "K1 Max", 1, U.CFS, true, true, 81, "Drawer front", Mix.PRINT),
    DemoPrinter("bambulab", "H2D", 2, U.AMS_HT, true, true, 60, "Tool holder", Mix.FAULT),
    DemoPrinter("bambulab", "A1 mini", 1, U.AMS, true, false, 100, "Keychain", Mix.DONE),
    DemoPrinter("klipper", "Trident", 1, U.NONE, true, true, 37, "Fan duct", Mix.FLAKY),
    DemoPrinter("prusa", "MK4S", 1, U.NONE, false, false, 23, "Hinge", Mix.PRINT),
    DemoPrinter("elegoo", "Centauri Carbon", 1, U.NONE, true, true, 49, "Desk organiser", Mix.PRINT),
    DemoPrinter("anycubic", "Kobra 3", 1, U.ACE, true, false, 12, "Headphone hook", Mix.PRINT),
    DemoPrinter("flashforge", "Adventurer 5M Pro", 1, U.NONE, true, true, 70, "Coaster", Mix.PRINT),
    DemoPrinter("creality", "Ender-3 V4", 1, U.NONE, true, false, 5, "Calibration cube", Mix.PRINT),
    DemoPrinter("bambulab", "P1S", 1, U.AMS, true, true, 91, "Bracket", Mix.PRINT),
)

/* Tiger Studio's palette and the usual materials, drawn anew at each start. */
private val PALETTE = intArrayOf(
    0x000000, 0x6E6E6E, 0xC4C4C4, 0xFFFFFF, 0x1A4E6E, 0xA9B6C1, 0x3DA4F0, 0x2226E6,
    0x2ED89A, 0x1A6E4A, 0x1EA51E, 0xBDE680, 0xFFE00A, 0xFF8A2A, 0x5C3A18, 0xA97430,
    0xEFE2CF, 0xCDC09A, 0xE8001C, 0xE8476E, 0xFF00FF, 0xC192D2, 0x7B00CC, 0x3D0B85,
)
private val MATERIAL = arrayOf("PLA", "PLA", "PLA", "PETG", "PETG", "ABS", "ASA", "TPU", "PLA+")

/** A print that moves: demo_snapshot() / demo_control(). Commands change only the picture. */
internal class DemoLink(private val k: Int, allPrinting: Boolean) : PrinterLink {

    private companion object {
        /** One per cent every 20 s. */
        const val PCT_EVERY_MS = 20_000L
        /** A finished print shows for 40 s, then the next starts (when every printer prints). */
        const val DONE_FOR_MS = 40_000L
        const val LAYERS = 240
        /** What the time left says: a four-hour print. */
        const val MINUTES_PER_PCT = 2
        const val FAULT_AFTER_MS = 120_000L
        const val ERROR_FOR_MS = 300_000L
        const val FLAKY_UP_MS = 90_000L
        const val FLAKY_DOWN_MS = 240_000L
        /** The low spool loses one per cent every 30 s while printing. */
        const val SPOOL_EVERY_MS = 30_000L
    }

    private val d = TABLE[k]
    private val mix = if (allPrinting) Mix.PRINT else d.mix
    private val rnd = Random()
    private val t0 = SystemClock.elapsedRealtime()

    private var state = when (mix) {
        Mix.PAUSE -> PvState.PAUSED
        Mix.IDLE -> PvState.IDLE
        Mix.DONE -> PvState.DONE
        else -> PvState.PRINTING
    }
    private var basePct = if (allPrinting && d.progress == 0) 50 else if (mix == Mix.DONE) 100 else d.progress
    private var baseMs = t0
    private var doneMs = if (mix == Mix.DONE) t0 else 0L
    /** A done plate that waits to be cleared does not restart on its own. */
    private val restartAfterDone = allPrinting
    private var light = d.light && rnd.nextBoolean()
    private var active = rnd.nextInt(4)
    private var faulted = false
    private var notice = ""
    private val units = Array(4) { PvUnit() }
    private var errorMs = 0L
    /** When the low spool last lost a per cent. */
    private var spoolStartMs = t0

    init {
        when (d.unit) {
            U.AMS -> fill(units[0], "AMS A", 4, active)
            U.CFS -> fill(units[0], "CFS", 4, active)
            U.CANVAS -> fill(units[0], "Canvas", 4, active)
            U.ACE -> fill(units[0], "ACE", 4, active)
            U.STATION -> { fill(units[0], PV_LABEL_EXT, 1, -1); fill(units[1], "Station", 4, active) }
            U.TOOLS -> fill(units[0], "E1-E4", 4, active)
            U.AFC -> fill(units[0], "AFC", 4, active)
            U.AMS_HT -> { fill(units[0], "AMS A", 4, active); fill(units[1], PV_LABEL_HT, 1, -1) }
            U.NONE -> Unit
        }
        if (mix == Mix.LOW_SPOOL && d.unit != U.NONE) units[0].slot[active].percent = 12
    }

    private fun fill(u: PvUnit, label: String, slots: Int, sel: Int) {
        u.present = true; u.label = label; u.slots = slots
        for (i in 0 until slots) {
            val s = u.slot[i]
            s.present = true
            s.color = PALETTE[rnd.nextInt(PALETTE.size)]
            s.percent = 15 + rnd.nextInt(86)
            s.material = MATERIAL[rnd.nextInt(MATERIAL.size)]
            s.selected = i == sel
        }
    }

    @Synchronized
    fun isPrinting() = state == PvState.PRINTING && online(SystemClock.elapsedRealtime())

    @Synchronized
    fun progressNow() = progress(SystemClock.elapsedRealtime())

    @Synchronized
    fun fail() {
        basePct = progress(SystemClock.elapsedRealtime())
        state = PvState.ERROR
        errorMs = SystemClock.elapsedRealtime()
        faulted = true
        notice = Tr.t("Filament runout", "Fin de filament")
    }

    @Synchronized
    fun finishNow() {
        if (state != PvState.PRINTING) return
        state = PvState.DONE
        basePct = 100
        doneMs = SystemClock.elapsedRealtime()
    }

    private fun online(now: Long): Boolean {
        if (mix != Mix.FLAKY) return true
        val t = (now - t0) % (FLAKY_UP_MS + FLAKY_DOWN_MS)
        return t < FLAKY_UP_MS
    }

    private fun progress(now: Long): Int {
        if (state != PvState.PRINTING) return basePct
        val p = basePct + ((now - baseMs) / PCT_EVERY_MS).toInt()
        if (p >= 100) {
            state = PvState.DONE // finished: shows as done for a while
            basePct = 100
            doneMs = now
            return 100
        }
        return p
    }

    /** A temperature that sits on its set point the way a real one does: a few tenths either side, slowly. */
    private fun near(target: Float, now: Long, seed: Int) =
        target + 0.6f * sin(now / 2300.0f + seed).toFloat()

    @Synchronized
    override fun snapshot(): PrinterView {
        val now = SystemClock.elapsedRealtime()
        if (state == PvState.DONE && restartAfterDone && now - doneMs > DONE_FOR_MS) {
            state = PvState.PRINTING // the next print starts
            basePct = 0
            baseMs = now
        }
        // Nobody can clear a fault from a demo: the printer is taken care of after a while.
        if (state == PvState.ERROR && now - errorMs > ERROR_FOR_MS) { state = PvState.IDLE; basePct = 0; notice = "" }
        if (mix == Mix.FAULT && !faulted && state == PvState.PRINTING && now - t0 > FAULT_AFTER_MS) fail()
        if (mix == Mix.LOW_SPOOL && state == PvState.PRINTING && d.unit != U.NONE) {
            val s = units[0].slot[active]
            val drop = ((now - spoolStartMs) / SPOOL_EVERY_MS).toInt()
            if (drop > 0 && s.percent > 2) { s.percent -= drop; spoolStartMs = now }
        }
        val pct = progress(now)
        val busy = state == PvState.PRINTING || state == PvState.PAUSED
        val v = PrinterView(name = d.name, brand = d.brand)
        if (!online(now)) {
            v.online = false
            v.notice = Tr.t("No answer from the printer", "L'imprimante ne répond pas")
            return v
        }
        v.online = true
        v.state = state
        v.progress = if (state == PvState.IDLE) 0 else pct
        v.file = if (state == PvState.IDLE) "" else d.file
        v.totalLayer = if (busy || state == PvState.DONE || state == PvState.ERROR) LAYERS else 0
        v.layer = if (v.totalLayer > 0) pct * LAYERS / 100 else 0
        v.leftS = if (busy) (100 - pct) * MINUTES_PER_PCT * 60 else 0
        val nt = if (busy) 220f else 0f
        val bt = if (busy) 60f else 0f
        v.nozzleTarget = nt
        v.nozzle = if (busy) near(nt, now, k) else 24f + k % 3
        v.bedTarget = bt
        v.bed = if (busy) near(bt, now, k + 7) else 23f + k % 2
        v.chamber = if (d.chamber) (if (busy) 34f + k % 3 else 25f) else 0f
        v.nozzleCount = d.nozzles
        v.nozzleActive = if (d.nozzles > 1) active % d.nozzles else -1
        if (d.nozzles > 1) {
            for (i in 0 until d.nozzles) {
                val atWork = busy && i == v.nozzleActive
                v.nozzles[i].target = if (atWork) nt else 0f
                v.nozzles[i].temp = if (atWork) v.nozzle else 38f + i
                v.nozzles[i].label = if (d.nozzles == 2) (if (i == 0) Tr.t("Left", "Gauche") else Tr.t("Right", "Droite")) else ""
            }
        }
        v.canControl = true
        v.canLight = d.light
        v.lightOn = d.light && light
        v.notice = if (state == PvState.ERROR) notice else ""
        v.noticeError = state == PvState.ERROR
        for (i in units.indices) v.unit[i] = units[i].copy()
        return v
    }

    @Synchronized
    override fun control(cmd: PvControl): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!online(now)) return false
        when (cmd) {
            PvControl.PAUSE -> if (state == PvState.PRINTING) { basePct = progress(now); state = PvState.PAUSED }
            // A resumed fault goes on printing: the spool was changed.
            PvControl.RESUME -> if (state == PvState.PAUSED || state == PvState.ERROR) {
                state = PvState.PRINTING; baseMs = now; notice = ""
            }
            PvControl.STOP -> { state = PvState.IDLE; basePct = 0; notice = "" }
            PvControl.LIGHT_ON, PvControl.LIGHT_OFF -> light = cmd == PvControl.LIGHT_ON
        }
        return true
    }

    override fun stop() = Demo.register(this, false)
}
