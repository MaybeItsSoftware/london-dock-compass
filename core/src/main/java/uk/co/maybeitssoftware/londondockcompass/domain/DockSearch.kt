package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * Finds docks by name, for picking a destination you are nowhere near yet.
 *
 * Every word of the query has to appear somewhere in the name, in any order — "strand craven"
 * finds "Craven Street, Strand". Names are compared with punctuation stripped, because TfL's data
 * spells the same comma three different ways ("River Street , Clerkenwell").
 *
 * Nearest first when we know where the rider is; alphabetical when we do not.
 */
fun searchDocks(
    docks: List<Dock>,
    query: String,
    from: GeoPoint?,
    limit: Int = 30
): List<Dock> {
    val words = normalise(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()

    val matches = docks.filter { dock ->
        val name = normalise(dock.name)
        words.all { it in name }
    }
    val ordered = if (from == null) {
        matches.sortedBy { it.name }
    } else {
        matches.sortedBy { from.distanceTo(it.position) }
    }
    return ordered.take(limit)
}

private fun normalise(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
