package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class WorkspacePolicyTest {
    @Test fun accentAndMultiWordSearchFindActualTools() {
        assertEquals("audio", ToolCatalog.search("microfone externo").single().key)
        assertTrue(ToolCatalog.search("exposicao").any { it.key == "exposure" })
        assertTrue(ToolCatalog.search("rgb waveform").any { it.key == "scopes" })
        assertTrue(ToolCatalog.search("sem correspondencia xyz").isEmpty())
    }
    @Test fun everyToolHasUniqueStableDestination() {
        assertEquals(ToolCatalog.tools.size, ToolCatalog.tools.map { it.key }.distinct().size)
        assertTrue(ToolCatalog.tools.all { it.page in setOf("PRESETS", "VIDEO", "AUDIO", "LOOK", "MONITOR", "SENSOR", "FOCUS", "ZOOM", "STABILIZATION", "PROJECT", "APP") })
        assertEquals(listOf("zoom", "focus"), ToolCatalog.favorites(listOf("zoom", "unknown", "focus", "zoom")).map { it.key })
    }
    @Test fun namesAndCountersAreBounded() {
        assertEquals("Cena", WorkspacePolicy.label("\n Cena\t"))
        assertEquals(56, WorkspacePolicy.label("a".repeat(200)).length)
        assertEquals(999999, WorkspacePolicy.nextTake(Int.MAX_VALUE))
        assertEquals(2, WorkspacePolicy.nextTake(1))
        assertEquals(64, WorkspacePolicy.splitMb(-1))
        assertEquals(3072, WorkspacePolicy.splitMb(Int.MAX_VALUE))
    }
    @Test fun presetCapacityPreservesExistingWorkAndStillAllowsReplacement() {
        val names = (1..WorkspacePolicy.MAX_PRESETS).map { "Preset $it" }
        assertFalse(WorkspacePolicy.canSavePreset(names, "Nova cena"))
        assertTrue(WorkspacePolicy.canSavePreset(names, "Preset 1"))
        assertTrue(WorkspacePolicy.canSavePreset(names, " \nPreset 24\t"))
        assertTrue(WorkspacePolicy.canSavePreset(names.dropLast(1), "Nova cena"))
        assertEquals((1..WorkspacePolicy.MAX_PRESETS).map { "Preset $it" }, names)
    }
}
