package io.github.ridelabz.engwespeedtool.ui.nav

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test
    fun pushAndPop() {
        val nav = Navigator(listOf(Route.WELCOME))
        nav.push(Route.RISK_ACCEPTANCE)
        nav.push(Route.RISK_ACCEPTANCE)
        assertEquals(listOf(Route.WELCOME, Route.RISK_ACCEPTANCE), nav.routes)
        assertTrue(nav.pop())
        assertEquals(Route.WELCOME, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
        assertEquals(Route.WELCOME, nav.current)
    }

    @Test
    fun resetReplacesTheStack() {
        val nav = Navigator(listOf(Route.WELCOME, Route.DONE))
        nav.reset(Route.DASHBOARD)
        assertEquals(listOf(Route.DASHBOARD), nav.routes)
    }

    @Test
    fun neverEmpty() {
        assertEquals(listOf(Route.WELCOME), Navigator(emptyList()).routes)
    }

    @Test
    fun savesAndRestoresTheStack() {
        val nav = Navigator(listOf(Route.DASHBOARD, Route.SETTINGS))
        val saved = with(Navigator.Saver) { SaverScope { true }.save(nav) }
        val restored = Navigator.Saver.restore(saved!!)
        assertEquals(nav.routes, restored!!.routes)
    }

    @Test
    fun aRestoredStackStopsBeforeScreensThatCannotResume() {
        val nav =
            Navigator(
                listOf(Route.WELCOME, Route.RISK_ACCEPTANCE, Route.BLUETOOTH, Route.FIND_BIKE, Route.CONNECT_CHECK),
            )
        val saved = with(Navigator.Saver) { SaverScope { true }.save(nav) }
        assertEquals(
            listOf(Route.WELCOME, Route.RISK_ACCEPTANCE, Route.BLUETOOTH),
            Navigator.Saver.restore(saved!!)!!.routes,
        )
    }
}
