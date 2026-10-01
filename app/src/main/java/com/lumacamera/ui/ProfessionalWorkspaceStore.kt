package com.lumacamera.ui

import android.content.Context
import com.lumacamera.core.WorkspacePolicy
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceSettings(
    val project: String = "", val scene: String = "", val take: Int = 1,
    val audioDeviceId: Int? = null, val splitEnabled: Boolean = false, val splitSizeMb: Int = 1024,
    val zoomA: Float = 1f, val zoomB: Float = 2f, val zoomSeconds: Float = 3f,
    val focusA: Float = 0f, val focusB: Float = 1f, val focusSeconds: Float = 2f,
    val lutFile: String? = null, val presetName: String = "",
    val favorites: List<String> = listOf("presets", "focus", "zoom")
)

/** Small local workspace state, independent from the capture switches and camera driver. */
class ProfessionalWorkspaceStore(context: Context) {
    private val store = context.getSharedPreferences("luma-workspace", Context.MODE_PRIVATE)
    fun load(): WorkspaceSettings = runCatching {
        val j = JSONObject(store.getString("workspace", "{}") ?: "{}")
        fun number(key: String, fallback: Float, low: Float, high: Float): Float = j.optDouble(key, fallback.toDouble()).toFloat()
            .takeIf { it.isFinite() }?.coerceIn(low, high) ?: fallback
        WorkspaceSettings(WorkspacePolicy.label(j.optString("project")), WorkspacePolicy.label(j.optString("scene")),
            j.optInt("take", 1).coerceIn(1, 999999), j.optInt("audio", -1).takeIf { it >= 0 },
            j.optBoolean("split"), WorkspacePolicy.splitMb(j.optInt("splitMb", 1024)),
            number("zoomA", 1f, 1f, 20f), number("zoomB", 2f, 1f, 20f), number("zoomSeconds", 3f, .1f, 120f),
            number("focusA", 0f, 0f, 100f), number("focusB", 1f, 0f, 100f), number("focusSeconds", 2f, .1f, 120f),
            j.optString("lut").takeIf { it.matches(Regex("lut_[a-f0-9]{16}\\.cube")) },
            WorkspacePolicy.label(j.optString("preset")),
            j.optJSONArray("favorites")?.let { a -> (0 until minOf(a.length(), 6)).map { a.optString(it) }.distinct() }
                ?: WorkspaceSettings().favorites)
    }.getOrDefault(WorkspaceSettings())

    fun save(v: WorkspaceSettings) {
        val j = JSONObject().put("project", WorkspacePolicy.label(v.project)).put("scene", WorkspacePolicy.label(v.scene))
            .put("take", v.take.coerceIn(1, 999999)).put("audio", v.audioDeviceId ?: -1)
            .put("split", v.splitEnabled).put("splitMb", WorkspacePolicy.splitMb(v.splitSizeMb))
            .put("zoomA", v.zoomA).put("zoomB", v.zoomB).put("zoomSeconds", v.zoomSeconds)
            .put("focusA", v.focusA).put("focusB", v.focusB).put("focusSeconds", v.focusSeconds)
            .put("lut", v.lutFile ?: "").put("preset", WorkspacePolicy.label(v.presetName))
            .put("favorites", JSONArray(v.favorites.take(6)))
        store.edit().putString("workspace", j.toString()).apply()
    }

    fun presets(): List<JSONObject> = runCatching {
        val array = JSONArray(store.getString("presets", "[]") ?: "[]")
        (0 until minOf(array.length(), WorkspacePolicy.MAX_PRESETS)).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    fun savePreset(name: String, capture: JSONObject, app: AppPreferences, workspace: WorkspaceSettings): Boolean {
        val label = WorkspacePolicy.label(name, "Meu preset")
        val previous = presets()
        if (!WorkspacePolicy.canSavePreset(previous.map { it.optString("name") }, label)) return false
        val item = JSONObject().put("name", label).put("capture", capture)
            .put("width", app.videoWidth).put("height", app.videoHeight).put("fps", app.videoFps)
            .put("microphone", app.microphoneEnabled).put("lut", workspace.lutFile ?: "")
            .put("zoomA", workspace.zoomA).put("zoomB", workspace.zoomB).put("zoomSeconds", workspace.zoomSeconds)
            .put("focusA", workspace.focusA).put("focusB", workspace.focusB).put("focusSeconds", workspace.focusSeconds)
        val items = previous.filter { it.optString("name") != label } + item
        store.edit().putString("presets", JSONArray(items).toString()).apply()
        return true
    }
    fun removePreset(name: String) = store.edit().putString("presets", JSONArray(presets().filter { it.optString("name") != name }).toString()).apply()

    fun sceneTake(project: String, scene: String): Int = runCatching {
        JSONObject(store.getString("takes", "{}") ?: "{}").optInt(sceneKey(project, scene), 1).coerceIn(1, 999999)
    }.getOrDefault(1)
    fun recordStarted(v: WorkspaceSettings): WorkspaceSettings {
        val next = WorkspacePolicy.nextTake(v.take)
        val j = runCatching { JSONObject(store.getString("takes", "{}") ?: "{}") }.getOrDefault(JSONObject())
        // Bound state growth for a small local app. Existing labels remain meaningful if the counter resets.
        if (j.length() >= 256 && !j.has(sceneKey(v.project, v.scene))) j.keys().asSequence().take(1).toList().forEach { j.remove(it) }
        j.put(sceneKey(v.project, v.scene), next)
        store.edit().putString("takes", j.toString()).apply()
        return v.copy(take = next).also { save(it) }
    }
    private fun sceneKey(project: String, scene: String) = JSONArray(listOf(WorkspacePolicy.label(project), WorkspacePolicy.label(scene))).toString()
}
