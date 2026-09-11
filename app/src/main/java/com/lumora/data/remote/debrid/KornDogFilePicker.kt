package com.lumora.data.remote.debrid

object KornDogFilePicker {

    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts"
    )

    private val unwantedLanguage = Regex(
        """\b(rus|russian|ukr|ukrainian|ita|italian|spa|spanish|latino|fre|fra|french|ger|deu|german|hin|hindi)\b""",
        RegexOption.IGNORE_CASE
    )

    private val wantedLanguage = Regex(
        """\b(eng|english|multi|multi[- .]?audio|dual[- .]?audio)\b""",
        RegexOption.IGNORE_CASE
    )

    private val junk = Regex(
        """\b(sample|trailer|featurette|extras?|preview)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun isExactEpisode(
        name: String,
        season: Int,
        episode: Int
    ): Boolean {
        val patterns = listOf(
            Regex(
                """\bS0?${season}E0?${episode}\b""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """\b0?${season}x0?${episode}\b""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns.any { it.containsMatchIn(name) }
    }

    fun score(
        name: String,
        size: Long,
        season: Int?,
        episode: Int?
    ): Int {
        val cleanName =
            name.substringBefore('?')
                .substringBefore('#')

        val lower = cleanName.lowercase()
        val ext = lower.substringAfterLast('.', "")

        if (ext !in videoExtensions) {
            return Int.MIN_VALUE
        }

        if (junk.containsMatchIn(lower)) {
            return Int.MIN_VALUE
        }

        /*
         * Series playback is strict:
         * if an exact episode is requested, a different episode is never
         * considered a valid fallback.
         */
        if (
            season != null &&
            episode != null &&
            !isExactEpisode(cleanName, season, episode)
        ) {
            return Int.MIN_VALUE
        }

        var score = 0

        if (season != null && episode != null) {
            score += 20_000
        }

        if (wantedLanguage.containsMatchIn(cleanName)) {
            score += 4_000
        }

        if (
            unwantedLanguage.containsMatchIn(cleanName) &&
            !wantedLanguage.containsMatchIn(cleanName)
        ) {
            score -= 12_000
        }

        when {
            "1080p" in lower -> score += 1_000
            "2160p" in lower || "4k" in lower -> score += 900
            "720p" in lower -> score += 600
            "480p" in lower -> score += 300
        }

        if (size > 0L) {
            score +=
                (size / 100_000_000L)
                    .coerceAtMost(500L)
                    .toInt()
        }

        return score
    }

    fun <T> choose(
        items: List<T>,
        season: Int?,
        episode: Int?,
        name: (T) -> String,
        size: (T) -> Long
    ): T? {
        return items
            .map { item ->
                item to score(
                    name(item),
                    size(item),
                    season,
                    episode
                )
            }
            .filter { it.second != Int.MIN_VALUE }
            .maxByOrNull { it.second }
            ?.first
    }
}
