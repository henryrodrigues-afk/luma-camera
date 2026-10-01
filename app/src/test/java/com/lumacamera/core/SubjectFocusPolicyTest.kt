package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectFocusPolicyTest {
    @Test fun unknownModesUseThePersonModel() {
        assertEquals(SubjectFocusPolicy.OBJECTS, SubjectFocusPolicy.normalizeMode(1))
        for (mode in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
            assertEquals(SubjectFocusPolicy.PEOPLE, SubjectFocusPolicy.normalizeMode(mode))
        }
    }

    @Test fun eitherAiToggleEnablesTheSharedMask() {
        assertFalse(SubjectFocusPolicy.isAiEnabled(false, false))
        for ((portrait, cinema) in listOf(true to false, false to true, true to true)) {
            assertTrue(SubjectFocusPolicy.isAiEnabled(portrait, cinema))
        }
    }

    @Test fun centerCoordinatesDoNotCountAsAnObjectSelection() {
        assertTrue(SubjectFocusPolicy.needsObjectSelection(1, false, .5f, .5f))
        assertFalse(SubjectFocusPolicy.shouldAnalyze(true, 1, false))
        assertFalse(SubjectFocusPolicy.shouldApplyMask(true, 1, false, maskValid = true))
    }

    @Test fun anExplicitPointCanSelectAnywhereInsideTheFrame() {
        for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
            assertTrue(SubjectFocusPolicy.selectionReady(1, true, x, y))
            assertFalse(SubjectFocusPolicy.needsObjectSelection(1, true, x, y))
        }
    }

    @Test fun invalidObjectCoordinatesNeverSelectOrApplyAMask() {
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            for ((x, y) in listOf(value to .5f, .5f to value)) {
                assertFalse(SubjectFocusPolicy.selectionReady(1, true, x, y))
                assertFalse(SubjectFocusPolicy.shouldAnalyze(true, 1, true, x, y))
                assertFalse(SubjectFocusPolicy.shouldApplyMask(true, 1, true, x, y, true))
            }
        }
    }

    @Test fun peopleDoNotRequireAnObjectPoint() {
        assertTrue(SubjectFocusPolicy.selectionReady(0, false, Float.NaN, Float.NaN))
        assertTrue(SubjectFocusPolicy.shouldAnalyze(true, 0, false))
        assertFalse(SubjectFocusPolicy.needsObjectSelection(0, false))
    }

    @Test fun aDisabledEffectNeverRunsOrAppliesTheMask() {
        for (mode in listOf(0, 1)) {
            assertFalse(SubjectFocusPolicy.shouldAnalyze(false, mode, true))
            assertFalse(SubjectFocusPolicy.shouldApplyMask(false, mode, true, maskValid = true))
        }
    }

    @Test fun anEmptyOrExpiredMaskCannotBlurTheWholeFrame() {
        for (mode in listOf(0, 1)) {
            assertFalse(SubjectFocusPolicy.shouldApplyMask(true, mode, true, maskValid = false))
            assertTrue(SubjectFocusPolicy.shouldApplyMask(true, mode, true, maskValid = true))
        }
    }

    @Test fun maskMessagesDoNotConfirmLensFocus() {
        for (kind in SubjectFocusPolicy.StatusKind.values()) for (mode in listOf(0, 1)) {
            val text = SubjectFocusPolicy.status(kind, mode, false, true)
            assertTrue(text.startsWith("Desfoque IA · "))
            assertFalse(text.contains("foco", ignoreCase = true))
            assertFalse(text.contains("confirmado", ignoreCase = true))
        }
    }

    @Test fun maskStatusDistinguishesPeopleObjectsAndBackground() {
        val valid = SubjectFocusPolicy.StatusKind.MASK_VALID
        assertEquals("Desfoque IA · pessoa preservada", SubjectFocusPolicy.status(valid, 0))
        assertEquals("Desfoque IA · objeto preservado", SubjectFocusPolicy.status(valid, 1))
        assertEquals("Desfoque IA · fundo preservado · transição ativa", SubjectFocusPolicy.status(valid, 1, true, true))
    }

    @Test fun aPendingOrFailedMaskDoesNotClaimAnActiveTransition() {
        for (kind in SubjectFocusPolicy.StatusKind.values().filter { it != SubjectFocusPolicy.StatusKind.MASK_VALID }) {
            assertFalse(SubjectFocusPolicy.status(kind, 1, true, true).contains("transição ativa"))
        }
        assertEquals("Desfoque IA · toque no objeto para selecionar",
            SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.WAITING_POINT, 1))
        assertEquals("Desfoque IA · nenhuma pessoa identificada",
            SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.EMPTY, 0))
        assertEquals("Desfoque IA · recorte perdido · selecione o objeto novamente",
            SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.EMPTY, 1))
        assertEquals("Desfoque IA · analisando pessoas",
            SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.WAITING_POINT, 0))
    }
}
