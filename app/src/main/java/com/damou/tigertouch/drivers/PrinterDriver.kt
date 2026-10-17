package com.damou.tigertouch.drivers

import com.damou.tigertouch.cloud.Cloud
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/*
 * One driver per way of reaching a printer: the firmware's `printer_driver_t`
 * (drivers/printer_driver.h). A driver turns a printer of the account into the
 * same [PrinterView] the dashboard draws.
 *
 * Unlike the ESP32, a tablet keeps every switched-on printer connected at once:
 * there is no load budget, each link runs on its own coroutines.
 */

enum class DrvKind {
    /** This app cannot reach it (yet). */
    NONE,
    /** On the local network. */
    LAN,
    /** Through the maker's cloud. */
    CLOUD,
}

/** What a driver may use. Shared by every link; nothing in it is per printer. */
class DriverEnv(
    /** For the makers' clouds: certificates checked the normal way. */
    val http: OkHttpClient,
    /**
     * For printers on the LAN: their certificates are self-signed (Bambu, Prusa
     * over HTTPS...), so this client accepts any certificate and any host name.
     * Never use it for an address on the Internet.
     */
    val lanHttp: OkHttpClient,
    /** The TigerTag account: Bambu cloud session, Anycubic identity. */
    val cloud: Cloud,
    /** Lives as long as the link manager; a link starts its jobs in a child of it. */
    val scope: CoroutineScope,
)

interface PrinterLink {
    /** A copy of the latest state (name left as the driver found it). Cheap, called every second. */
    fun snapshot(): PrinterView

    /**
     * Pause, resume, stop or the light. false when the driver sends no command
     * or is not connected; true when the command was handed to the connection
     * (the printer's own report says whether it was obeyed).
     */
    fun control(cmd: PvControl): Boolean = false

    /** Closes the connection and stops every job of this link. Called once. */
    fun stop()
}

interface PrinterDriver {
    /** Matches [AccountPrinter.brand]. */
    val brand: String

    fun support(p: AccountPrinter): DrvKind

    /** Starts connecting at once and keeps reconnecting on its own until [PrinterLink.stop]. */
    fun start(p: AccountPrinter, env: DriverEnv): PrinterLink
}
