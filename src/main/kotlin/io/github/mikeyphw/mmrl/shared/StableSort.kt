package io.github.mikeyphw.mmrl.shared

import java.util.Locale

/** Locale-independent, deterministic ordering suitable for both module lists. */
object StableModuleOrder {
    fun compare(nameA: String, idA: String, nameB: String, idB: String): Int {
        val a = nameA.lowercase(Locale.ROOT)
        val b = nameB.lowercase(Locale.ROOT)
        return a.compareTo(b).takeIf { it != 0 }
            ?: idA.compareTo(idB).takeIf { it != 0 }
            ?: nameA.compareTo(nameB)
    }
}
