import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/MainActivityPlayer.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-castprepare-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

pattern = re.compile(r"localFile\s*=\s*castTranscodeFile\s*,")
matches = list(pattern.finditer(src))

if len(matches) != 1:
    print(f"FAILED: expected exactly 1 match for 'localFile = castTranscodeFile,', found {len(matches)}. Aborting, no changes written.")
    sys.exit(1)

replacement = (
    "localFileProvider = { castTranscodeFile },\n"
    "                    onPreparing = {\n"
    "                        runOnUiThread {\n"
    "                            castHandoffLog(\"HANDOFF_PREPARING\")\n"
    "                            Toast.makeText(\n"
    "                                this@setupPlayerControls,\n"
    "                                \"Preparing video for Cast…\",\n"
    "                                Toast.LENGTH_SHORT\n"
    "                            ).show()\n"
    "                        }\n"
    "                    },"
)

src = pattern.sub(replacement, src, count=1)

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Patched cast call site successfully.")
