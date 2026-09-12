package com.lumora.data.remote.debrid

object KornDogFilePicker {

    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts"
    )

    private val unwantedLanguage = Regex(
        """\b(rus|russian|ukr|ukrainian|ita|italian|spa|spanish|latino|fre|fra|french|ger|deu|german|hin|hindi|jpn|japanese|kor|korean|chi|zho|chinese)\b""",
        RegexOption.IGNORE_CASE
    )

    /*
     * Explicit English is strong evidence.
     * Multi/Dual Audio is only a ranking hint because it does not
     * prove that one of the tracks is English.
     */
    private val englishLanguage = Regex(
        """\b(eng|english)\b""",
        RegexOption.IGNORE_CASE
    )

    private val multiLanguage = Regex(
        """\b(multi|multi[- .]?audio|dual[- .]?audio)\b""",
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

        val explicitEnglish =
            englishLanguage.containsMatchIn(cleanName)

        val multiAudio =
            multiLanguage.containsMatchIn(cleanName)

        val explicitForeign =
            unwantedLanguage.containsMatchIn(cleanName)

        /*
         * KornDog FenLite language ranking.
         *
         * Explicit English:
         *     very strong preference
         *
         * Multi / Dual Audio:
         *     useful hint only
         *
         * Explicit foreign language without English:
         *     severe penalty
         *
         * Final proof comes from the actual Media3 audio tracks.
         */
        if (explicitEnglish) {
            score += 8_000
        }

        if (multiAudio) {
            score += 1_000
        }

        if (explicitForeign && !explicitEnglish) {
            score -= 20_000
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
