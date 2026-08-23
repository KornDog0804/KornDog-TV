import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/MainActivityCatalog.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-hidechannels-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

pattern = re.compile(
    r"val rawLive = list\.filter \{ ch ->\s*"
    r"ch\.mediaType == MediaType\.LIVE &&\s*"
    r"!ch\.name\.contains\(\"##\"\) &&\s*"
    r"!\(hideAdult && isAdultCategory\(ch\.categoryName, ch\.group\)\)\s*"
    r"\}",
    re.DOTALL
)
matches = pattern.findall(src)
if len(matches) != 1:
    print(f"FAILED: expected exactly 1 match for rawLive filter block, found {len(matches)}. Aborting.")
    sys.exit(1)

replacement = (
    "val hiddenLiveChannelIds = com.lumora.cache.HiddenChannelsStore.getHiddenChannelIds(this)\n"
    "    val rawLive = list.filter { ch ->\n"
    "        ch.mediaType == MediaType.LIVE &&\n"
    "        !ch.name.contains(\"##\") &&\n"
    "        !(hideAdult && isAdultCategory(ch.categoryName, ch.group)) &&\n"
    "        ch.id !in hiddenLiveChannelIds\n"
    "    }"
)
src = pattern.sub(replacement, src, count=1)

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Patched rawLive filter in MainActivityCatalog.kt successfully.")
