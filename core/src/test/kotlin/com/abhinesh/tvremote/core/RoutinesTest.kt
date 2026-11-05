package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.voice.VoiceAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutinesTest {
    private val apps = AppShortcut.DEFAULTS

    @Test fun defaultRoutinesMatchTheirDescriptions() {
        val (movie, gaming, quiet) = RoutineSteps.DEFAULTS
        assertEquals("Open Netflix", RoutineSteps.summary(movie, apps))
        assertEquals("Switch to HDMI 2", RoutineSteps.summary(gaming, apps))
        assertEquals("Lower volume", RoutineSteps.summary(quiet, apps))
        assertEquals(VoiceAction.OpenApp(apps.first { it.id == "netflix" }), RoutineSteps.toAction(movie.steps[0], apps))
        assertEquals(VoiceAction.Key(244), RoutineSteps.toAction(gaming.steps[0], apps))
        assertEquals(VoiceAction.Volume(false, 5), RoutineSteps.toAction(quiet.steps[0], apps))
    }

    @Test fun everyOfferedStepRuns() {
        for (step in RoutineSteps.choices(apps)) {
            val a = RoutineSteps.toAction(step, apps)
            assert(a != null) { "no action for $step" }
            assert(RoutineSteps.describe(step, apps) != "Unknown step") { step }
        }
        assertNull(RoutineSteps.toAction("open:missing-app", apps))
    }
}
