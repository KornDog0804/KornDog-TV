import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/MainActivity.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-hidechannels-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

changes = 0

# 1. Channel long-press: was direct favourite-toggle, now opens a chooser
pattern1 = re.compile(r"onChannelLongPress\s*=\s*\{\s*channel\s*->\s*toggleFavoriteChannel\(channel\)\s*\}\s*,")
if len(pattern1.findall(src)) != 1:
    print(f"FAILED: expected exactly 1 match for onChannelLongPress wiring, found {len(pattern1.findall(src))}. Aborting.")
    sys.exit(1)
src = pattern1.sub("onChannelLongPress = { channel -> showChannelContextMenu(channel) },", src, count=1)
changes += 1

# 2. Category long-press: was tab==0 special-cased to pin-only, now always shows the full menu
pattern2 = re.compile(
    r"onCategoryLongClick = \{ category ->[\s\S]*?showCategoryContextMenu\(category\)\s*\}",
    re.DOTALL
)
matches2 = pattern2.findall(src)
if len(matches2) != 1:
    print(f"FAILED: expected exactly 1 match for onCategoryLongClick block, found {len(matches2)}. Aborting.")
    sys.exit(1)

replacement2 = (
    "onCategoryLongClick = { category ->\n"
    "            // Category-level pin/hide menu is available on every tab now - Live TV's\n"
    "            // dynamic buckets/brand rows fall back to hiding by their own synthetic id,\n"
    "            // same as everywhere else; raw provider categories (countries/languages)\n"
    "            // hide via matchIds exactly like Films/Series already did.\n"
    "            showCategoryContextMenu(category)\n"
    "        }"
)
src = pattern2.sub(replacement2, src, count=1)
changes += 1

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print(f"Applied {changes} edits to MainActivity.kt successfully.")
