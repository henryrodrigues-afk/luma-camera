package com.lumacamera.core

/** Explicit starting points for segmentation blur, never a claim of optical depth or lens focus. */
object AutomaticBlurPolicy {
    const val NATURAL = 0
    const val BALANCED = 1
    const val STRONG = 2

    data class Tuning(
        val strength: Float,
        val stability: Float,
        val edgeSoftness: Float,
        val transitionSeconds: Float,
        val quality: Int = 0,
    )

    // All profiles use the light analysis path. Increasing blur must not silently demand a heavier model.
    private val people = listOf(
        Tuning(.22f, .42f, .20f, .7f),
        Tuning(.36f, .50f, .26f, .85f),
        Tuning(.52f, .56f, .30f, 1f),
    )
    private val objects = listOf(
        Tuning(.18f, .48f, .14f, .7f),
        Tuning(.32f, .56f, .20f, .85f),
        Tuning(.46f, .62f, .24f, 1f),
    )

    fun normalizeStyle(style: Int): Int = if (style in NATURAL..STRONG) style else NATURAL

    fun tuning(subjectMode: Int, style: Int): Tuning {
        val profiles = if (SubjectFocusPolicy.normalizeMode(subjectMode) == SubjectFocusPolicy.OBJECTS) objects else people
        return profiles[normalizeStyle(style)]
    }
}
