import pathlib

path = pathlib.Path("app/src/main/java/com/lumora/data/remote/stremio/StremioAddonClient.kt")
text = path.read_text()

old = "title = stream.title,"
new = "title = stream.behaviorHints?.filename ?: stream.title,"

count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly 1 match, found {count} — aborting, check the file manually")

path.write_text(text.replace(old, new, 1))
print("Patched torrentResults() title field.")
