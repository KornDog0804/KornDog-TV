import re

path = "app/src/main/java/com/lumora/data/remote/StremioAddonClient.kt"
with open(path, "r") as f:
    content = f.read()

old = """<exact snippet from the real function>"""
new = """<same snippet, with audit block inserted>"""

if old not in content:
    raise SystemExit("Anchor text not found — file may differ from expected")

content = content.replace(old, new, 1)

with open(path, "w") as f:
    f.write(content)

print("Patched successfully")
