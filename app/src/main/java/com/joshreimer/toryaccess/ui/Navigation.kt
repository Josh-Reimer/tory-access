package com.joshreimer.toryaccess.ui

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Hub : Screen
    data class EditHost(val hostId: String?) : Screen
    data object Keys : Screen
    data object Terminal : Screen
    data object Tor : Screen
    data object Settings : Screen
}

/** A tiny back stack; the app has six screens and no deep links worth a nav library. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Hub)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        if (current == screen) return
        // Re-opening the terminal shouldn't stack duplicates.
        if (screen == Screen.Terminal) stack.remove(Screen.Terminal)
        stack.add(screen)
    }

    fun pop() {
        if (canPop) stack.removeAt(stack.lastIndex)
    }
}
