package com.lumacamera.core

/** Digital subject selection only. A valid mask never confirms the physical lens focus. */
object SubjectFocusPolicy {
    const val PEOPLE = 0
    const val OBJECTS = 1

    enum class StatusKind { FAILED, WAITING_POINT, ANALYZING, EMPTY, MASK_VALID }

    fun normalizeMode(value: Int): Int = if (value == OBJECTS) OBJECTS else PEOPLE

    fun isAiEnabled(portraitEnabled: Boolean, cinematicEnabled: Boolean): Boolean =
        portraitEnabled || cinematicEnabled

    /** Defaults do not select an object. The caller must record an explicit, in-frame tap. */
    fun selectionReady(mode: Int, pointSelected: Boolean, x: Float = .5f, y: Float = .5f): Boolean =
        normalizeMode(mode) == PEOPLE || (pointSelected && validCoordinate(x) && validCoordinate(y))

    fun needsObjectSelection(mode: Int, pointSelected: Boolean, x: Float = .5f, y: Float = .5f): Boolean =
        normalizeMode(mode) == OBJECTS && !selectionReady(mode, pointSelected, x, y)

    fun shouldAnalyze(enabled: Boolean, mode: Int, pointSelected: Boolean, x: Float = .5f, y: Float = .5f): Boolean =
        enabled && selectionReady(mode, pointSelected, x, y)

    /** Empty, expired or rejected masks must not turn the complete frame into background blur. */
    fun shouldApplyMask(
        enabled: Boolean, mode: Int, pointSelected: Boolean, x: Float = .5f, y: Float = .5f,
        maskValid: Boolean
    ): Boolean = shouldAnalyze(enabled, mode, pointSelected, x, y) && maskValid

    /** Reserve "foco confirmado" for Camera2 AF results; these labels describe the blur mask. */
    fun status(kind: StatusKind, mode: Int, focusBackground: Boolean = false, dynamic: Boolean = false): String {
        val objects = normalizeMode(mode) == OBJECTS
        val description = when (kind) {
            StatusKind.FAILED -> "segmentação indisponível"
            StatusKind.WAITING_POINT -> if (objects) "toque no objeto para selecionar" else "analisando pessoas"
            StatusKind.ANALYZING -> if (objects) "recortando objeto" else "analisando pessoas"
            StatusKind.EMPTY -> if (objects) "recorte perdido · selecione o objeto novamente" else "nenhuma pessoa identificada"
            StatusKind.MASK_VALID -> when {
                focusBackground -> "fundo preservado"
                objects -> "objeto preservado"
                else -> "pessoa preservada"
            }
        }
        val transition = if (dynamic && kind == StatusKind.MASK_VALID) " · transição ativa" else ""
        return "Desfoque IA · $description$transition"
    }

    private fun validCoordinate(value: Float): Boolean = value.isFinite() && value in 0f..1f
}
