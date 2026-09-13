picker_path = "app/src/main/java/com/lumora/data/remote/debrid/KornDogFilePicker.kt"
plugins_path = "app/src/main/java/com/lumora/MainActivityPlugins.kt"

# 1. Make isExactEpisode accessible from outside the object
with open(picker_path, "r") as f:
    picker = f.read()

old_picker = "    private fun isExactEpisode("
new_picker = "    fun isExactEpisode("

if picker.count(old_picker) != 1:
    raise SystemExit(f"picker anchor matched {picker.count(old_picker)} times, expected 1")

picker = picker.replace(old_picker, new_picker, 1)

with open(picker_path, "w") as f:
    f.write(picker)

# 2. Add exact-episode validation to hasDirectIdentity gate
with open(plugins_path, "r") as f:
    plugins = f.read()

old_gate = """            val hasDirectIdentity =
                !entry.directUrl.isNullOrBlank() ||
                    (
                        entry.resolver == "direct" &&
                            (
                                entry.result.token.startsWith(
                                    "https://",
                                    ignoreCase = true
                                ) ||
                                entry.result.token.startsWith(
                                    "http://",
                                    ignoreCase = true
                                )
                            )
                    )"""

new_gate = """            val directLooksPlayable =
                !entry.directUrl.isNullOrBlank() ||
                    (
                        entry.resolver == "direct" &&
                            (
                                entry.result.token.startsWith(
                                    "https://",
                                    ignoreCase = true
                                ) ||
                                entry.result.token.startsWith(
                                    "http://",
                                    ignoreCase = true
                                )
                            )
                    )

            val directMatchesRequestedEpisode =
                effectiveSeason == null ||
                    effectiveEpisode == null ||
                    com.lumora.data.remote.debrid.KornDogFilePicker.isExactEpisode(
                        entry.result.title,
                        effectiveSeason,
                        effectiveEpisode
                    )

            val hasDirectIdentity =
                directLooksPlayable && directMatchesRequestedEpisode

            if (directLooksPlayable && !directMatchesRequestedEpisode) {
                android.util.Log.d(
                    "KornDogNative",
                    "AUTO rejected direct candidate wrong episode " +
                        "requested=S${effectiveSeason}E${effectiveEpisode} " +
                        "title=${entry.result.title}"
                )
            }"""

if plugins.count(old_gate) != 1:
    raise SystemExit(f"plugins anchor matched {plugins.count(old_gate)} times, expected 1 — aborting, picker.kt already edited, revert manually if needed")

plugins = plugins.replace(old_gate, new_gate, 1)

with open(plugins_path, "w") as f:
    f.write(plugins)

print("Both edits applied successfully")
