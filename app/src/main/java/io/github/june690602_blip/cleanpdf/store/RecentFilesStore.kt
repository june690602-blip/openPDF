package io.github.june690602_blip.cleanpdf.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One recent entry. Immutable. [path] = the app-cache file we copied the PDF into. */
data class RecentFile(val path: String, val name: String, val ts: Long, val format: String = "PDF")

/** Pure list logic (newest-first, dedup by name, capped) + JSON (de)serialization. */
object RecentFilesLogic {
    // Dedup by NAME, not path: the same source file gets a fresh timestamped cache path on every
    // open (see PdfSource.copyToCache), so path-dedup would show one entry per open. Name is the
    // user-facing identity ("같은 파일은 하나로").
    fun add(current: List<RecentFile>, item: RecentFile, max: Int): List<RecentFile> =
        (listOf(item) + current.filterNot { it.name == item.name }).take(max)

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
        val before = list()
        val next = RecentFilesLogic.add(before, RecentFile(path, name, System.currentTimeMillis(), format), max)
        // Delete cache files orphaned by name-dedup / cap eviction (in before, dropped from next) —
        // never the file we just opened.
        val kept = next.mapTo(HashSet()) { it.path }
        before.forEach { if (it.path != path && it.path !in kept) runCatching { File(it.path).delete() } }
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }

    fun remove(path: String) {
        runCatching { File(path).delete() }  // drop the cached copy too
        val next = list().filterNot { it.path == path }
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }
}
