package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class AutomaticBlurPolicyTest {
    @Test fun presetsIncreaseBlurWithoutDemandingHeavierAnalysisOrWideHalos() {
        for (mode in listOf(SubjectFocusPolicy.PEOPLE, SubjectFocusPolicy.OBJECTS)) {
            val profiles = (AutomaticBlurPolicy.NATURAL..AutomaticBlurPolicy.STRONG).map {
                AutomaticBlurPolicy.tuning(mode, it)
            }
            assertTrue(profiles.zipWithNext().all { (a, b) -> a.strength < b.strength })
            assertTrue(profiles.all { it.quality == 0 && it.strength in .1f.. .6f })
            assertTrue(profiles.all { it.stability in .3f.. .65f && it.edgeSoftness in .1f.. .3f })
            assertTrue(profiles.all { it.transitionSeconds in .5f..1f })
        }
    }

    @Test fun objectContoursUseLessBlurAndSofterExpansionThanPeople() {
        for (style in AutomaticBlurPolicy.NATURAL..AutomaticBlurPolicy.STRONG) {
            val person = AutomaticBlurPolicy.tuning(SubjectFocusPolicy.PEOPLE, style)
            val item = AutomaticBlurPolicy.tuning(SubjectFocusPolicy.OBJECTS, style)
            assertTrue(item.strength < person.strength)
            assertTrue(item.edgeSoftness < person.edgeSoftness)
        }
    }

    @Test fun invalidSelectionsFallBackToModestNaturalPeople() {
        assertEquals(AutomaticBlurPolicy.tuning(SubjectFocusPolicy.PEOPLE, AutomaticBlurPolicy.NATURAL),
            AutomaticBlurPolicy.tuning(-99, Int.MAX_VALUE))
        assertTrue(AutomaticBlurPolicy.tuning(SubjectFocusPolicy.PEOPLE, AutomaticBlurPolicy.NATURAL).strength <= .25f)
    }
}
