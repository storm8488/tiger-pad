package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter

/**
 * The registry: the firmware's drivers/drivers.c. [forPrinter] returns the
 * first match by brand: two drivers can share a brand (Elegoo's CC2 and CC1) as
 * long as their `support()` are disjoint.
 */
object Drivers {
    private val REGISTRY: List<PrinterDriver> = listOf(
        CrealityDriver,
        BambuDriver,
        SnapmakerDriver,
        ElegooDriver,
        ElegooSdcpDriver,
        FlashForgeDriver,
        PrusaDriver,
        AnycubicDriver,
        KlipperDriver,
        OctoPrintDriver,
    )

    /** null when no driver can reach this printer. */
    fun forPrinter(p: AccountPrinter): PrinterDriver? =
        if (p.demo) com.damou.tigertouch.demo.Demo.DRIVER
        else REGISTRY.firstOrNull { it.brand == p.brand && it.support(p) != DrvKind.NONE }
}
