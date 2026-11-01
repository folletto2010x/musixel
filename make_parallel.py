#!/usr/bin/env python3
"""
Gives the build its own app id and name, so it installs next to the original
Pixel Music instead of replacing it. Run from the repo root, before Gradle.
"""
import glob, re, sys

NEW_ID = "com.unshoo.pixelmusic.musixel"
NEW_NAME = "Musixel"
OLD_ID = "com.unshoo.pixelmusic"


def edit(path, pattern, replacement, expected=None):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    new_text, count = re.subn(pattern, replacement, text)
    if count == 0 or (expected is not None and count != expected):
        sys.exit("%s: expected %s replacement(s), made %d" % (path, expected or ">=1", count))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    return count


# 1. The id Android tells apps apart by. The code's package (namespace) stays.
edit("app/build.gradle.kts",
     r'applicationId = "%s"' % re.escape(OLD_ID),
     'applicationId = "%s"' % NEW_ID, expected=1)

# 2. Static shortcuts name their target app: without this they would open the original.
edit("app/src/main/res/xml/shortcuts.xml",
     r'android:targetPackage="%s"' % re.escape(OLD_ID),
     'android:targetPackage="%s"' % NEW_ID)

# 3. The name under the launcher icon, in every language.
total = 0
for path in sorted(glob.glob("app/src/main/res/values*/strings.xml")):
    with open(path, encoding="utf-8") as f:
        if 'name="app_name"' not in f.read():
            continue
    total += edit(path, r'(<string name="app_name"[^>]*>)[^<]*(</string>)', r"\g<1>%s\g<2>" % NEW_NAME)
print("app id -> %s | name -> %s (%d language files)" % (NEW_ID, NEW_NAME, total))
