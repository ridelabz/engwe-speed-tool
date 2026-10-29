package io.github.ridelabz.engwespeedtool.ui.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Every screen of the app. Sheets and dialogs open on top of a screen and are not routes. */
enum class Route {
    WELCOME,
    RISK_ACCEPTANCE,
    BLUETOOTH,
    FIND_BIKE,
    CONNECT_CHECK,
    UNVERIFIED_ACKNOWLEDGEMENT,
    DONE,
    DASHBOARD,
    DIAGNOSTICS,
    LOG_PREVIEW,
    COMPATIBILITY_CHECK,
    SETTINGS,
    LEGAL,
    ABOUT,
    FIRMWARE_CAVEAT,
    LICENCES,
}

/** A back stack of [Route]s. The last entry is on screen; the stack is never empty. */
@Stable
class Navigator(
    initial: List<Route>,
) {
    private val stack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(Route.WELCOME) }) }

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    val routes: List<Route> get() = stack.toList()

    fun push(route: Route) {
        if (current != route) stack.add(route)
    }

    /** Goes back one screen; false when there is nothing to go back to. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replaces the whole stack, for example when onboarding ends. */
    fun reset(vararg routes: Route) {
        require(routes.isNotEmpty())
        stack.clear()
        stack.addAll(routes)
    }

    companion object {
        /**
         * Screens that show something running (a search, a check) can't resume after Android ends the app
         * process: the restored stack stops before the first of them.
         */
        private val TRANSIENT =
            setOf(
                Route.FIND_BIKE,
                Route.CONNECT_CHECK,
                Route.UNVERIFIED_ACKNOWLEDGEMENT,
                Route.LOG_PREVIEW,
                Route.COMPATIBILITY_CHECK,
            )

        val Saver: Saver<Navigator, Any> =
            listSaver(
                save = { navigator -> navigator.routes.map { it.ordinal } },
                restore = { saved ->
                    Navigator(saved.mapNotNull { Route.entries.getOrNull(it) }.takeWhile { it !in TRANSIENT })
                },
            )
    }
}
