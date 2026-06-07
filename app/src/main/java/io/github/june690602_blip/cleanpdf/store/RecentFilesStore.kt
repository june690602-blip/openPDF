package io.github.june690602_blip.cleanpdf.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recent entry. Immutable. [path] = the app-cache file we copied the PDF into. */
data class RecentFile(val path: String, val name: String, val ts: Long, val format: String = "PDF")

/** Pure list logic (newest-first, dedup by path, capped) + JSON (de)serialization. */
object RecentFilesLogic {
    fun add(current: List<RecentFile>, item: RecentFile, max: Int): List<RecentFile> =
        (listOf(item) + current.filterNot { it.path == item.path }).take(max)

    fun serialize(list: List<RecentFile>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("p", it.path).put("n", it.name).put("t", it.ts).put("f", it.format)) }
        return arr.toString()
    }

    fun deserialize(json: String): List<RecentFile> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentFile(o.getString("p"), o.getString("n"), o.getLong("t"), o.optString("f", "PDF"))
        }
    }.getOrDefault(emptyList())
}

/** SharedPreferences-backed store. Returns immutable lists. */
class RecentFilesStore(context: Context, private val max: Int = 10) {
    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = RecentFilesLogic.deserialize(prefs.getString("items", "[]")!!)

    fun add(path: String, name: String, format: String = "PDF") {
        val next = RecentFilesLogic.add(list(), RecentFile(path, name, System.currentTimeMillis(), format), max)
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }

    fun remove(path: String) {
        val next = list().filterNot { it.path == path }
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }
}
