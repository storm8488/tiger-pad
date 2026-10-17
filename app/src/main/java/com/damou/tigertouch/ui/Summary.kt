package com.damou.tigertouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.fleet.FleetFilter
import com.damou.tigertouch.fleet.SortBy
import com.damou.tigertouch.fleet.StateFilter
import com.damou.tigertouch.fleet.Summary
import com.damou.tigertouch.model.Brands
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.delay

/** The colours of a summary counter: its state's. */
private fun filterColors(f: StateFilter): Pair<Color, Color> = when (f) {
    StateFilter.PRINTING -> stateColors(PvState.PRINTING, true)
    StateFilter.PAUSED -> stateColors(PvState.PAUSED, true)
    StateFilter.ERROR -> stateColors(PvState.ERROR, true)
    StateFilter.DONE -> stateColors(PvState.DONE, true)
    StateFilter.LOW_SPOOL -> Color(0xFF4A2E16) to Pal.accent
    StateFilter.IDLE -> stateColors(PvState.IDLE, true)
    StateFilter.OFFLINE -> Color(0xFF2B2F38) to Pal.danger
}

/**
 * The summary under the header: a counter per state, each a filter in one
 * touch ("12 printing · 1 paused · 1 error..."), and the next print to end.
 */
@Composable
fun SummaryBar(summary: Summary, filter: FleetFilter, onFilter: (FleetFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((f, n) in summary.counts) {
            val on = filter.state == f
            val (bg, fg) = filterColors(f)
            Row(
                Modifier
                    .height(40.dp)
                    .background(bg, RoundedCornerShape(50))
                    .then(if (on) Modifier.border(2.dp, fg, RoundedCornerShape(50)) else Modifier)
                    .clickable { onFilter(filter.copy(state = if (on) null else f)) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(fg, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(f.label(n), color = if (on) Pal.text else fg, fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
        if (filter.active) {
            Row(
                Modifier.height(40.dp).clickable { onFilter(filter.copy(state = null, brand = null)) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Close, null, tint = Pal.muted, modifier = Modifier.size(18.dp))
                Text(Tr.t("All", "Tout"), color = Pal.muted, fontSize = 16.sp)
            }
        }
        if (summary.nextEndMin > 0) NextEnd(summary.nextName, summary.nextEndMin, Modifier.weight(1f))
    }
}

/**
 * "Next end: K1 Max in 14 min (20:02)", counted down once a minute, at the
 * right of the bar. When the counters leave too little room, "K1 Max · 14 min",
 * and nothing when even that does not fit.
 */
@Composable
private fun NextEnd(name: String, endMin: Long, modifier: Modifier) {
    var nowMin by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); nowMin = System.currentTimeMillis() / 60_000 }
    }
    val inMin = (endMin - nowMin).toInt().coerceAtLeast(0)
    val left = if (inMin < 1) Tr.t("now", "maintenant") else Tr.t("in ", "dans ") + fmtLeftMin(inMin)
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Tr.t("Next end: ", "Prochaine fin : "), color = Pal.muted, fontSize = 16.sp, maxLines = 1)
                Text("$name ", color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("$left (${fmtClockMin(endMin)})", color = Pal.text, fontSize = 16.sp, maxLines = 1)
            }
            Text(
                "$name · " + if (inMin < 1) Tr.t("now", "maintenant") else fmtLeftMin(inMin),
                color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        },
        modifier = modifier,
    ) { children, c ->
        val free = Constraints(maxHeight = c.maxHeight)
        val pick = children.asSequence().map { it.measure(free) }.firstOrNull { it.width <= c.maxWidth }
        layout(c.maxWidth, pick?.height ?: 0) {
            pick?.place(c.maxWidth - pick.width, 0)
        }
    }
}

/** A header button that opens a menu: the sort, the brand. */
@Composable
private fun MenuChip(icon: ImageVector?, label: String, active: Boolean, items: List<Pair<String, () -> Unit>>, selected: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(44.dp)
                .background(if (active) Pal.tile else Pal.card, RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Pal.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = if (active) Pal.accent else Pal.text, fontSize = 16.sp, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Pal.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, (text, act) ->
                DropdownMenuItem(
                    text = {
                        Text(text, fontSize = 18.sp, color = if (i == selected) Pal.accent else Pal.text,
                            fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    onClick = { open = false; act() },
                    modifier = Modifier.height(52.dp),
                )
            }
        }
    }
}

@Composable
fun SortMenu(filter: FleetFilter, onFilter: (FleetFilter) -> Unit) {
    MenuChip(
        Icons.AutoMirrored.Filled.Sort, filter.sort.label(), active = false,
        items = SortBy.entries.map { s -> s.label() to { onFilter(filter.copy(sort = s)) } },
        selected = filter.sort.ordinal,
    )
}

@Composable
fun BrandMenu(brands: List<String>, filter: FleetFilter, onFilter: (FleetFilter) -> Unit) {
    if (brands.size < 2 && filter.brand == null) return
    val all = Tr.t("All brands", "Toutes marques")
    MenuChip(
        null, filter.brand?.let { Brands.label(it) } ?: all, active = filter.brand != null,
        items = listOf(all to { onFilter(filter.copy(brand = null)) }) +
            brands.map { b -> Brands.label(b) to { onFilter(filter.copy(brand = b)) } },
        selected = filter.brand?.let { brands.indexOf(it) + 1 } ?: 0,
    )
}
