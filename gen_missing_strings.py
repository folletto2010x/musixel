#!/usr/bin/env python3
"""
Writes app/src/main/res/values/strings_fallback_generated.xml with every
string the code refers to but the default (values/) resources do not define.

Source for each missing key, in order: the Turkish resources, any other
locale, then the English texts in EXTRA below. Run from the repo root.
"""
import glob, os, re, sys

RES = "app/src/main/res"
SRC = "app/src/main"

EXTRA = {
    "about_link_source_subtitle": "View the source code on GitHub",
    "about_support_title": "Support the project",
    "auth_navidrome_server_placeholder": "https://your-server.example.com",
    "language_zh_rCN": "简体中文",
    "musicbrainz_applied": "MusicBrainz tags applied",
    "musicbrainz_choose_match": "Choose a match",
    "musicbrainz_lookup": "Look up on MusicBrainz",
    "musicbrainz_lookup_failed": "Lookup failed",
    "musicbrainz_match_score": "Match score: %1$s",
    "musicbrainz_no_results": "No matches found",
    "musicbrainz_save_failed_fallback": "Could not save the tags",
    "musicbrainz_search_failed_fallback": "Search failed",
    "musicbrainz_searching": "Searching MusicBrainz…",
    "setcat_about_pixelplayer_title": "About Pixel Music",
    "setcat_language_arabic": "العربية",
    "setcat_language_norwegian_bokmal": "Norsk bokmål",
    "setcat_language_turkish": "Türkçe",
    "setup_external_services_subtitle": "Optional online services you can turn on later",
    "song_info_ringtone_permission_prompt": "Permission is needed to set this song as a ringtone.",
    "song_info_tone_picker_body": "Choose where to use this song as a tone.",
}

OUT = os.path.join(RES, "values", "strings_fallback_generated.xml")


def read(path):
    with open(path, encoding="utf-8-sig", errors="ignore") as f:
        return f.read()


def definitions(directory):
    found = {}
    for path in sorted(glob.glob(os.path.join(RES, directory, "*.xml"))):
        if os.path.abspath(path) == os.path.abspath(OUT):
            continue
        text = read(path)
        for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>.*?</string>', text, re.S):
            found[m.group(1)] = m.group(0)
        for m in re.finditer(r'<plurals\s+name="([^"]+)".*?</plurals>', text, re.S):
            found["plurals:" + m.group(1)] = m.group(0)
    return found


def referenced():
    refs = set()
    for path in glob.glob(os.path.join(SRC, "java", "**", "*.kt"), recursive=True):
        text = read(path)
        refs |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", text))
        refs |= {"plurals:" + x for x in re.findall(r"R\.plurals\.([A-Za-z0-9_]+)", text)}
    for path in glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True) + [os.path.join(SRC, "AndroidManifest.xml")]:
        refs |= set(re.findall(r"@string/([A-Za-z0-9_]+)", read(path)))
    return refs


def main():
    base = definitions("values")
    missing = sorted(k for k in referenced() if k not in base)
    locales = {d: definitions(d) for d in sorted(os.listdir(RES))
               if d.startswith("values-") and d != "values-night"}
    ordered = ["values-tr"] + [d for d in locales if d != "values-tr"]

    lines, stats = [], {"copied": 0, "extra": 0, "unresolved": []}
    for key in missing:
        element = next((locales[d][key] for d in ordered if key in locales.get(d, {})), None)
        if element is not None:
            stats["copied"] += 1
            lines.append("    " + element)
        elif key in EXTRA:
            stats["extra"] += 1
            lines.append('    <string name="%s">%s</string>' % (key, EXTRA[key]))
        else:
            stats["unresolved"].append(key)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        f.write("\n".join(lines) + "\n</resources>\n")
    print("referenced but undefined: %d | copied from other locales: %d | written here: %d | still unresolved: %d"
          % (len(missing), stats["copied"], stats["extra"], len(stats["unresolved"])))
    if stats["unresolved"]:
        print("unresolved:", ", ".join(stats["unresolved"]))
        sys.exit(1)


if __name__ == "__main__":
    main()
