package dev.hdwatch.buttonmap.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hdwatch.buttonmap.input.Symbol

sealed interface Route {
    data object Pad : Route
    data object Menu : Route
    data object MappingList : Route
    data class MappingEdit(val symbol: Symbol) : Route
    data object MacroList : Route
    data object Hid : Route
    data object Settings : Route
    data object Log : Route
}

/** Manual navigation stack; readable synchronously by the activity for input routing. */
class AppNav {
    var current: Route by mutableStateOf(Route.Pad)
        private set

    private val stack = ArrayDeque<Route>()

    fun push(route: Route) {
        stack.addLast(current)
        current = route
    }

    fun pop() {
        current = stack.removeLastOrNull() ?: Route.Pad
    }

    /** Jump back to root without touching the pad state machine beyond a reset. */
    fun popToPad() {
        stack.clear()
        current = Route.Pad
    }
}
