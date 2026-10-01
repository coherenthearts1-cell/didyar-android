package com.didyar.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class SavedDidyarProject(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val scenes: List<ScenePoint>
)

object ProjectStore {
    private const val PREFS = "didyar_project_store"
    private const val KEY_ACTIVE_PROJECT = "active_project_v1"

    fun save(
        context: Context,
        uri: Uri,
        fileName: String,
        durationMs: Long,
        scenes: List<ScenePoint>
    ) {
        val sceneArray = JSONArray()
        scenes.forEach { scene ->
            sceneArray.put(
                JSONObject().apply {
                    put("index", scene.index)
                    put("timeMs", scene.timeMs)
                    put("description", scene.description)
                }
            )
        }

        val root = JSONObject().apply {
            put("uri", uri.toString())
            put("fileName", fileName)
            put("durationMs", durationMs)
            put("savedAt", System.currentTimeMillis())
            put("scenes", sceneArray)
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PROJECT, root.toString())
            .apply()
    }

    fun load(context: Context): SavedDidyarProject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROJECT, null)
            ?: return null

        return runCatching {
            val root = JSONObject(raw)
            val uriText = root.getString("uri")
            val scenesJson = root.optJSONArray("scenes") ?: JSONArray()
            val scenes = buildList {
                for (i in 0 until scenesJson.length()) {
                    val item = scenesJson.getJSONObject(i)
                    add(
                        ScenePoint(
                            index = item.optInt("index", i + 1),
                            timeMs = item.optLong("timeMs", 0L),
                            description = item.optString("description", "")
                        )
                    )
                }
            }

            SavedDidyarProject(
                uri = Uri.parse(uriText),
                fileName = root.optString("fileName", "فیلم ذخیره‌شده"),
                durationMs = root.optLong("durationMs", 0L),
                scenes = scenes
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_PROJECT)
            .apply()
    }
}
