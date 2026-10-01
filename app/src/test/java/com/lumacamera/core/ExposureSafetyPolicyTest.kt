package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class ExposureSafetyPolicyTest {
    @Test fun compatibleDeviceStepsChooseAModestNegativeBias() {
        assertEquals(-1, ExposureSafetyPolicy.compensationSteps(-6, 6, 1f / 3f))
        assertEquals(-2, ExposureSafetyPolicy.compensationSteps(-6, 6, 1f / 6f))
        assertEquals(-1, ExposureSafetyPolicy.compensationSteps(-6, 6, .5f))
    }

    @Test fun narrowDeviceRangesAreRespectedWithoutMakingTheBiasPositive() {
        assertEquals(-1, ExposureSafetyPolicy.compensationSteps(-1, 1, .1f))
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(0, 6, 1f / 3f))
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(-6, -1, 1f / 3f))
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(0, 0, 1f / 3f))
    }

    @Test fun coarseStepsCannotDarkenByAFullStopUnderASafeLabel() {
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(-3, 3, 1f))
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(-3, 3, .6f))
    }

    @Test fun malformedHardwareLimitsStayNeutral() {
        for (step in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            assertEquals(0, ExposureSafetyPolicy.compensationSteps(-6, 6, step))
        }
        assertEquals(0, ExposureSafetyPolicy.compensationSteps(3, -3, .333f))
    }

    @Test fun validBiasAlwaysFitsTheDeviceAndNeverExceedsHalfAStop() {
        for (step in listOf(.01f, .1f, .25f, 1f / 3f, .5f, .6f, 1f, 2f)) {
            for (minimum in -8..0) {
                val steps = ExposureSafetyPolicy.compensationSteps(minimum, 3, step)
                assertTrue(steps in minimum..0)
                assertTrue(steps * step >= -.500001f)
            }
        }
    }
}
