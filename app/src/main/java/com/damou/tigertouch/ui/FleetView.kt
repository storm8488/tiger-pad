package com.damou.tigertouch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.damou.tigertouch.fleet.PrinterUi
import kotlinx.coroutines.flow.StateFlow

/**
 * The fleet as the screens read it. A new list arrives whenever any printer
 * changes; reading it whole would recompose every tile. So each printer has a
 * state of its own, written only when that printer changed, and the order of
 * the printers has its own too: a refresh that changes one printer recomposes
 * the one tile that reads it, nothing around it.
 */
@Stable
class FleetView {
    private val one = HashMap<String, MutableState<PrinterUi?>>()
    private val _ids = mutableStateOf<List<String>>(emptyList())

    /** The printers' ids, in the order the account gives them. */
    val ids: List<String> get() = _ids.value

    /** This printer; the reader recomposes only when it changes. */
    fun get(id: String): PrinterUi? = state(id).value

    private fun state(id: String) = one.getOrPut(id) { mutableStateOf(null) }

    /** Called on the main thread with each new list. */
    fun update(list: List<PrinterUi>) {
        for (p in list) state(p.id).value = p // equal values write nothing
        val ids = list.map { it.id }
        if (ids != _ids.value) {
            _ids.value = ids
            one.keys.retainAll(ids.toSet())
        }
    }
}

/** A [FleetView] fed by [flow] for as long as the caller is on screen. */
@Composable
fun rememberFleetView(flow: StateFlow<List<PrinterUi>>): FleetView {
    val v = remember { FleetView().also { it.update(flow.value) } }
    LaunchedEffect(flow) { flow.collect { v.update(it) } }
    return v
}

/** Kept for the screens' calls: the printer, read as its own state. */
@Composable
fun FleetView.printer(id: String): PrinterUi? = get(id)

/**
 * The order to show: [ids], except while a finger scrolls. A list that
 * reorders under the finger loses what was being read, and costs a layout of
 * every row on the frame; the new order comes when the scroll stops.
 */
@Composable
fun heldWhileScrolling(ids: List<String>, scrolling: Boolean): List<String> {
    val shown = remember { mutableStateOf(ids) }
    LaunchedEffect(ids, scrolling) { if (!scrolling) shown.value = ids }
    // A printer that comes or goes is shown at once, scroll or not.
    val held = shown.value
    return if (held.size != ids.size || !held.containsAll(ids)) ids else held
}
