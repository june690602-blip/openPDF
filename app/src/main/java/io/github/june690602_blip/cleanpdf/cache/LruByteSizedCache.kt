package io.github.june690602_blip.cleanpdf.cache

/**
 * LRU cache bounded by total byte size. Not thread-safe; guard externally.
 * @param maxBytes eviction threshold
 * @param sizeOf bytes a value occupies
 * @param onEvict optional hook for releasing evicted values (e.g. Bitmap.recycle)
 */
class LruByteSizedCache<K, V>(
    private val maxBytes: Int,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
    private val sizeOf: (V) -> Int,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)?.let { old ->
            currentBytes -= sizeOf(old)
            onEvict(key, old)
        }
        map[key] = value
        currentBytes += sizeOf(value)
        trim()
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (currentBytes > maxBytes && it.hasNext()) {
            val e = it.next()       // eldest first (access order)
            it.remove()
            currentBytes -= sizeOf(e.value)
            onEvict(e.key, e.value)
        }
    }

    fun clear() {
        map.entries.forEach { onEvict(it.key, it.value) }
        map.clear(); currentBytes = 0
    }
}
