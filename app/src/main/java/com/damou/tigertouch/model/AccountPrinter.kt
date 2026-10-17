package com.damou.tigertouch.model

/**
 * One printer of the TigerTag account, as the firmware's `account_printer_t`
 * (net/cloud.h) holds it. Read from Firestore by [com.damou.tigertouch.cloud.Cloud].
 */
data class AccountPrinter(
    /** creality, bambulab, flashforge, snapmaker, elegoo, anycubic, prusa, klipper, octoprint */
    val brand: String,
    /** The account's document id. */
    val id: String,
    /** printerName */
    val name: String,
    /** LAN address, `host` or `host:port`; empty for cloud printers. */
    val ip: String = "",
    /** TigerTag catalogue id. */
    val modelId: String = "",
    /** Serial number, or the account's id when it has none. */
    val sn: String = "",
    /** Access / check code, when the brand uses one. */
    val cc: String = "",
    /** User name, when the brand has one (PrusaLink, Anycubic). */
    val user: String = "",
    /** Anycubic: the 32-hex device id of the broker topics. */
    val devId: String = "",
    /** Anycubic: its own numeric model id (the other half of the topics). */
    val acuModel: String = "",
    /** API key: OctoPrint (required), Moonraker (optional), PrusaLink (legacy). */
    val key: String = "",
    /** Elegoo: "sdcp" for a Centauri Carbon 1; empty for MQTT (CC2). */
    val proto: String = "",
    /** Elegoo SDCP: the MainboardID that comes with discovery. */
    val boardId: String = "",
    /** Reachable only through the maker's cloud. */
    val cloud: Boolean = false,
    /** A made-up printer of the demo mode: never reached, whatever its brand. */
    val demo: Boolean = false,
)

object Brands {
    /** Tiger Studio's order: the brands, then its printer plugins. */
    val ALL = listOf(
        "creality", "flashforge", "bambulab", "snapmaker", "elegoo",
        "anycubic", "prusa", "klipper", "octoprint",
    )

    fun label(brand: String): String = when (brand) {
        "creality" -> "Creality"
        "bambulab" -> "Bambu Lab"
        "flashforge" -> "FlashForge"
        "snapmaker" -> "Snapmaker"
        "elegoo" -> "Elegoo"
        "anycubic" -> "Anycubic"
        "prusa" -> "Prusa"
        "klipper" -> "Klipper"
        "octoprint" -> "OctoPrint"
        else -> brand
    }
}
