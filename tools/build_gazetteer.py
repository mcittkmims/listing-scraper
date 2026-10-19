#!/usr/bin/env python3
"""Builds the street and place gazetteer used by the address normalizer.

Downloads named streets and settlements of Moldova from OpenStreetMap (Overpass API)
and writes two resource files:

  src/main/resources/gazetteer/md-places.tsv
  src/main/resources/gazetteer/md-streets.tsv.gz

Run it again to refresh the data:  python3 tools/build_gazetteer.py
Data © OpenStreetMap contributors, available under the Open Database License (ODbL).
"""
import csv
import gzip
import io
import math
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

OVERPASS = "https://overpass-api.de/api/interpreter"
USER_AGENT = "apartment-lens-gazetteer/1.0 (+https://github.com/mcittkmims/apartment-lens)"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/gazetteer"

STREETS_QUERY = """
[out:csv(::id,::lat,::lon,"highway","name","name:ro","name:ru","old_name","old_name:ru","alt_name","alt_name:ru";true;"\\t")][timeout:600];
area["ISO3166-1"="MD"][admin_level=2]->.md;
way["highway"]["name"](area.md);
out center;
"""

PLACES_QUERY = """
[out:csv(::id,::lat,::lon,"place","name","name:ro","name:ru","population";true;"\\t")][timeout:300];
area["ISO3166-1"="MD"][admin_level=2]->.md;
node["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"](area.md);
out;
"""

# Street types, as written in OpenStreetMap names. The code is shared with the Java normalizer.
RO_TYPES = [
    ("strada", "street"), ("str.", "street"), ("stada", "street"),
    ("stradela", "lane"), ("bulevardul", "boulevard"), ("șoseaua", "highway"),
    ("calea", "avenue"), ("piața", "square"), ("aleea", "alley"),
    ("drumul", "road"), ("drum", "road"), ("fundătura", "dead_end"),
    ("intrarea", "passage"), ("pasajul", "passage"), ("trecerea", "passage"),
    ("tupicul", "dead_end"), ("ulița", "street"),
]
RU_TYPES = [
    ("улица", "street"), ("переулок", "lane"), ("проспект", "boulevard"),
    ("бульвар", "boulevard"), ("шоссе", "highway"), ("площадь", "square"),
    ("тупик", "dead_end"), ("проезд", "passage"), ("аллея", "alley"),
    ("спуск", "street"), ("тракт", "road"), ("каля", "avenue"),
    ("ул.", "street"), ("пер.", "lane"), ("пр-т", "boulevard"), ("просп.", "boulevard"),
    ("бул.", "boulevard"), ("ш.", "highway"), ("пл.", "square"),
]
SKIP_HIGHWAYS = {"track", "path", "footway", "cycleway", "steps", "bridleway", "proposed", "abandoned"}
# Roads named after a route number or the places they connect are not addresses.
ROUTE_NAME = re.compile(r"^[A-Z]{1,2}\d|\b(de acc?ess?|centura|ocolire|traseul)\b|[–—]\s*[A-Z]\d", re.I)
ROUTE_CODE = re.compile(r"^[A-Z]{1,2}\d+$")


def place_keys(places_text):
    keys = set()
    for r in csv.DictReader(io.StringIO(places_text), delimiter="\t"):
        for k in ("name", "name:ro", "name:ru"):
            if r.get(k):
                keys.add(fold(r[k]))
    return keys


def fold(s):
    import unicodedata
    s = fix_diacritics(s).lower().replace("â", "î")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    return re.sub(r"[^\w]+", " ", s).strip()


def is_connector(name, places):
    """'Călinești-Fălești', 'Dănceni-R3': a road between places, not a street. Every part must be a
    place or a route code, so 'Schinoasa-Vale' and person names ('Bănulescu-Bodoni') stay."""
    parts = [p for p in re.split(r"\s*[-–—]\s*", name) if p]
    return len(parts) >= 2 and all(fold(p) in places or ROUTE_CODE.match(p.strip()) for p in parts)


def fetch(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(5):
        try:
            req = urllib.request.Request(OVERPASS, data, {"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=900) as resp:
                return resp.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001 - retry any network failure
            print(f"overpass attempt {attempt + 1} failed: {e}", file=sys.stderr)
            time.sleep(30 * (attempt + 1))
    sys.exit("overpass unavailable")


def fix_diacritics(s):
    return s.replace("ş", "ș").replace("Ş", "Ș").replace("ţ", "ț").replace("Ţ", "Ț")


def split_type(name, types):
    """Removes leading (or, for Russian, trailing) street-type words. Returns (type, rest)."""
    s = name.strip()
    found = None
    changed = True
    while changed:
        changed = False
        low = s.lower()
        for word, code in types:
            if low.startswith(word + " "):
                s = s[len(word):].strip()
                found = found if found and code == "street" else code
                changed = True
                break
            if low.endswith(" " + word):
                s = s[: -len(word)].strip()
                found = found or code
                changed = True
                break
    return found, s


def clean(name):
    return re.sub(r"\s+", " ", fix_diacritics(name or "")).strip()


def km(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    return 6371 * math.hypot(dlat, dlon)


def build_places(text):
    rows = csv.DictReader(io.StringIO(text), delimiter="\t")
    out = []
    for r in rows:
        name = clean(r["name:ro"] or r["name"])
        if not name:
            continue
        pop = re.sub(r"\D", "", r["population"] or "")
        out.append((name, clean(r["name:ru"]), r["place"], r["@lat"], r["@lon"], pop))
    out.sort()
    with open(OUT / "md-places.tsv", "w", encoding="utf-8", newline="") as f:
        f.write("# Settlements of Moldova. Data © OpenStreetMap contributors, ODbL.\n")
        f.write("name_ro\tname_ru\tplace\tlat\tlon\tpopulation\n")
        for row in out:
            f.write("\t".join(row) + "\n")
    print(f"places: {len(out)}")


def build_streets(text, places):
    rows = csv.DictReader(io.StringIO(text), delimiter="\t")
    # Group ways by Romanian name, then split each group into clusters of nearby ways:
    # one street is drawn as many ways, and different villages reuse the same names.
    groups = defaultdict(list)
    for r in rows:
        if r["highway"] in SKIP_HIGHWAYS:
            continue
        ro = clean(r["name:ro"] or r["name"])
        if not ro or (re.search(r"[А-Яа-яЁё]", ro) and not r["name:ro"]):
            # Cyrillic-only main name (Transnistria, Gagauzia): keep it as the Russian name.
            ru_only = clean(r["name"])
            ro = ""
        else:
            ru_only = ""
        ro_type, ro_name = split_type(ro, RO_TYPES) if ro else (None, "")
        if ROUTE_NAME.search(ro or ru_only) or is_connector(ro_name or ru_only, places):
            continue
        ru_type, ru_name = split_type(clean(r["name:ru"]) or ru_only, RU_TYPES)
        aliases = set()
        for key in ("old_name", "alt_name"):
            if r[key]:
                for part in r[key].split(";"):
                    aliases.add(split_type(clean(part), RO_TYPES)[1])
        for key in ("old_name:ru", "alt_name:ru"):
            if r[key]:
                for part in r[key].split(";"):
                    aliases.add(split_type(clean(part), RU_TYPES)[1])
        for key in ("old_name", "alt_name"):
            if r[key] and re.search(r"[А-Яа-яЁё]", r[key]):  # Russian text in a Romanian tag
                for part in r[key].split(";"):
                    aliases.discard(split_type(clean(part), RO_TYPES)[1])
                    aliases.add(split_type(clean(part), RU_TYPES)[1])
        street_type = ro_type or ru_type or "street"
        name_key = (ro_name or ru_name).lower()
        if not name_key:
            continue
        groups[(street_type, name_key)].append(
            (float(r["@lat"]), float(r["@lon"]), ro_name, ru_name, aliases))

    out = []
    for (street_type, _), ways in groups.items():
        clusters = single_link(ways, 1.5)
        for c in clusters:
            ro = most_common([w[2] for w in c["ways"] if w[2]])
            ru = most_common([w[3] for w in c["ways"] if w[3]])
            aliases = set().union(*(w[4] for w in c["ways"]))
            for w in c["ways"]:  # other spellings found on the same street
                if w[2] and w[2] != ro:
                    aliases.add(w[2])
                if w[3] and w[3] != ru:
                    aliases.add(w[3])
            aliases.discard(ro)
            aliases.discard(ru)
            aliases.discard("")
            extent = max(km(c["lat"], c["lon"], w[0], w[1]) for w in c["ways"])
            out.append((street_type, ro, ru, "|".join(sorted(aliases)),
                        f"{c['lat']:.6f}", f"{c['lon']:.6f}", f"{extent:.2f}", str(len(c["ways"]))))
    out.sort(key=lambda r: (r[1] or r[2], r[0], r[4]))
    with gzip.open(OUT / "md-streets.tsv.gz", "wt", encoding="utf-8", newline="") as f:
        f.write("# Named streets of Moldova. Data © OpenStreetMap contributors, ODbL.\n")
        f.write("type\tname_ro\tname_ru\taliases\tlat\tlon\textent_km\tways\n")
        for row in out:
            f.write("\t".join(row) + "\n")
    print(f"streets: {len(out)}")


def single_link(ways, max_km):
    """Clusters ways so that each way is within max_km of some other way of its cluster."""
    parent = list(range(len(ways)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(ways)):
        for j in range(i + 1, len(ways)):
            if km(ways[i][0], ways[i][1], ways[j][0], ways[j][1]) < max_km:
                parent[find(i)] = find(j)
    members = defaultdict(list)
    for i, w in enumerate(ways):
        members[find(i)].append(w)
    clusters = []
    for group in members.values():
        lat = sum(w[0] for w in group) / len(group)
        lon = sum(w[1] for w in group) / len(group)
        clusters.append({"lat": lat, "lon": lon, "ways": group})
    return clusters


def most_common(values):
    if not values:
        return ""
    counts = defaultdict(int)
    for v in values:
        counts[v] += 1
    return max(sorted(counts), key=lambda v: counts[v])


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if len(sys.argv) == 3:  # offline rebuild from previously downloaded files
        places_text = Path(sys.argv[1]).read_text(encoding="utf-8")
        streets_text = Path(sys.argv[2]).read_text(encoding="utf-8")
    else:
        places_text = fetch(PLACES_QUERY)
        streets_text = fetch(STREETS_QUERY)
    build_places(places_text)
    build_streets(streets_text, place_keys(places_text))


if __name__ == "__main__":
    main()
