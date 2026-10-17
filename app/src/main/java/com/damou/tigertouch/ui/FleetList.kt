package com.damou.tigertouch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.damou.tigertouch.model.PvControl

/** The list: one row per printer ([ListRow]), the firmware's list view. */
@Composable
fun FleetList(fleet: FleetView, ids: List<String>, alerts: AlertView, onOpen: (String) -> Unit, onControl: (String, PvControl) -> Unit) {
    val d = LocalDensity.current
    val pens = remember(d) { Pens(d) }
    val state = rememberLazyListState()
    val shown = heldWhileScrolling(ids, state.isScrollInProgress)
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shown, key = { it }) { id -> ListRow(fleet, id, pens, alerts, onOpen, onControl) }
    }
}
