package dev.buddy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerInputValidationTest {
    @Test fun pointsAreIntsInsideTheDisplay() {
        assertEquals(540 to 1200, validatePoint(540.0, 1200.9, 1080, 2340))
        assertEquals(0 to 0, validatePoint(0.0, 0.0, 1080, 2340))
        assertEquals(1079 to 2339, validatePoint(1082.0, 2345.0, 1080, 2340))    // a few pixels past the edge: clamped
        assertEquals(1079 to 5, validatePoint(1079.9, 5.0, 1080, 2340))
    }

    @Test fun badPointsAreRejected() {
        for ((x, y) in listOf(Double.NaN to 5.0, 5.0 to Double.NaN, Double.POSITIVE_INFINITY to 1.0, 1.0 to Double.NEGATIVE_INFINITY,
            -1.0 to 5.0, 5.0 to -0.5, 1088.0 to 5.0, 5.0 to 2348.0, 5000.0 to 5000.0)) {
            assertThrows("$x,$y", ToolError::class.java) { validatePoint(x, y, 1080, 2340) }
        }
        assertThrows(ToolError::class.java) { validatePoint(1.0, 1.0, 0, 2340) }
    }

    @Test fun swipeDurationIsHumanScale() {
        assertEquals(50, clampSwipeMs(-5)); assertEquals(300, clampSwipeMs(300)); assertEquals(2000, clampSwipeMs(99_999))
    }
}

class KeyWhitelistTest {
    @Test fun onlyTheListedKeysPass() {
        for (code in listOf(KEY_BACK, KEY_HOME, KEY_APP_SWITCH, KEY_ENTER, KEY_DEL, 19, 20, 21, 22, 23, 24, 25, 85, 87, 88, 126, 127)) assertTrue("$code", isAllowedKey(code))
        for (code in listOf(0, 26 /* power */, 82 /* menu */, 91 /* mute */, 223 /* sleep */, 224 /* wakeup */, 187 + 1, -4, Int.MAX_VALUE)) assertFalse("$code", isAllowedKey(code))
        assertEquals(17, ALLOWED_KEYCODES.size)
    }

    @Test fun theKeyCommandRefusesAnythingElse() {
        assertEquals(listOf("input", "keyevent", "4"), inputKeyCommand(KEY_BACK))
        assertThrows(IllegalArgumentException::class.java) { inputKeyCommand(26) }
    }
}

class InputCommandTest {
    @Test fun commandsAreNumbersOnly() {
        val commands = listOf(inputTapCommand(540, 1200), inputSwipeCommand(10, 20, 30, 40, 300), inputSwipeCommand(5, 5, 5, 5, 600), inputKeyCommand(KEY_HOME))
        for (c in commands) {
            assertEquals("input", c[0])
            assertTrue(c[1] in setOf("tap", "swipe", "keyevent"))
            assertTrue(c.toString(), c.drop(2).all { Regex("-?\\d+").matches(it) })
        }
        assertEquals(listOf("input", "tap", "540", "1200"), inputTapCommand(540, 1200))
        assertEquals(listOf("input", "swipe", "10", "20", "30", "40", "2000"), inputSwipeCommand(10, 20, 30, 40, 99_999))
        assertTrue(commands.all { c -> c.none { it.any { ch -> ch in ";|&$`<>\\ \n\"'" } } })   // nothing a shell would act on
    }
}

class InputPathChoiceTest {
    @Test fun shizukuOnlyWhenEverythingIsInPlace() {
        assertEquals(InputPath.SHIZUKU, chooseInputPath(true, true, true, true))
        for (mask in 0 until 15) {   // every combination except all-true
            val p = chooseInputPath(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            assertEquals("mask $mask", InputPath.ACCESSIBILITY, p)
        }
    }

    @Test fun statusMapping() {
        assertEquals(PowerStatus.NOT_INSTALLED, powerStatus(false, true, true, false))
        assertEquals(PowerStatus.NOT_RUNNING, powerStatus(true, false, true, false))
        assertEquals(PowerStatus.PERMISSION_NEEDED, powerStatus(true, true, false, false))
        assertEquals(PowerStatus.READY, powerStatus(true, true, true, false))
        assertEquals(PowerStatus.DEGRADED, powerStatus(true, true, true, true))
        assertEquals(listOf("Not installed", "Not running", "Permission needed", "Ready", "Degraded"), PowerStatus.entries.map { it.label })
    }
}

class RouteInputTest {
    @Test fun noHelperMeansAccessibility() = runBlocking {
        var degraded = 0
        val r = routeInput<Boolean>(null, 100, { degraded++ }) { true }
        assertEquals(InputPath.ACCESSIBILITY, r.path); assertTrue(r.value); assertEquals(0, degraded)
    }

    @Test fun aWorkingHelperCarriesTheAction() = runBlocking {
        var fallbackRan = false
        val r = routeInput<Boolean>({ true }, 100, { error("not degraded") }) { fallbackRan = true; true }
        assertEquals(InputPath.SHIZUKU, r.path); assertFalse(fallbackRan)
    }

    @Test fun aThrowingHelperFallsBackAndIsMarkedDegraded() = runBlocking {
        var degraded = 0
        val r = routeInput<Boolean>({ throw IllegalStateException("binder died") }, 100, { degraded++ }) { true }
        assertEquals(InputPath.ACCESSIBILITY, r.path); assertEquals(1, degraded)
    }

    @Test fun aSlowHelperTimesOutAndFallsBack() = runBlocking {
        var degraded = 0
        val r = routeInput<Boolean>({ delay(5_000); true }, 50, { degraded++ }) { true }
        assertEquals(InputPath.ACCESSIBILITY, r.path); assertEquals(1, degraded)
        assertEquals(1500L, POWER_TIMEOUT_MS)
    }
}
