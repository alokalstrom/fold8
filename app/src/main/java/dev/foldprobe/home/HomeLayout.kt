package dev.foldprobe.home

/** Fixed slots survive catalog changes. A selected existing app swaps with the target slot. */
object HomeLayout {
    const val SIZE = 20
    fun normalize(saved: List<String?>): List<String?> {
        val seen = mutableSetOf<String>()
        return List(SIZE) { index -> saved.getOrNull(index)?.takeIf { it.isNotBlank() && seen.add(it) } }
    }
    fun assign(slots: List<String?>, index: Int, component: String?): List<String?> {
        require(index in 0 until SIZE)
        val next = normalize(slots).toMutableList()
        val previous = next[index]
        val other = if (component != null) next.indexOf(component) else -1
        if (other >= 0 && other != index) next[other] = previous
        next[index] = component
        return next
    }
}
