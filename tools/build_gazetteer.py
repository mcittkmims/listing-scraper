#!/usr/bin/env python3
"""Builds the street and place gazetteer used by the address normalizer.

Downloads named streets, settlements and address points of Moldova from OpenStreetMap
(Overpass API) and writes three resource files:

  src/main/resources/gazetteer/md-places.tsv        towns, villages, city districts
  src/main/resources/gazetteer/md-streets.tsv.gz    streets with Romanian, Russian and old names
  src/main/resources/gazetteer/md-addresses.tsv.gz  house numbers with coordinates, by street

Run it again to refresh the data:  python3 tools/build_gazetteer.py
Rebuild from saved downloads:      python3 tools/build_gazetteer.py places.tsv streets.tsv addresses.tsv
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

ADDRESSES_QUERY = """
[out:csv(::id,::lat,::lon,"addr:street","addr:housenumber","addr:city";true;"\\t")][timeout:900];
area["ISO3166-1"="MD"][admin_level=2]->.md;
nwr["addr:housenumber"]["addr:street"](area.md);
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
            out.append([street_type, ro, ru, aliases, c["lat"], c["lon"], extent, len(c["ways"])])
    return out


def add_addresses(streets, text, places):
    """Links each address point to its street (same name, nearest within 2 km). Names that only exist
    on addresses become streets of their own, clustered like roads. Returns (street index, number, lat, lon)."""
    index = defaultdict(list)
    for i, st in enumerate(streets):
        for name in [st[1], st[2], *st[3]]:
            if name:
                index[fold(name)].append(i)
    points, orphans = [], defaultdict(list)
    for r in csv.DictReader(io.StringIO(text), delimiter="\t"):
        raw = clean(r["addr:street"])
        number = clean(r["addr:housenumber"])
        if not raw or not number:
            continue
        lat, lon = float(r["@lat"]), float(r["@lon"])
        city = clean(r.get("addr:city"))
        cyrillic = re.search(r"[А-Яа-яЁё]", raw)
        street_type, name = split_type(raw, RU_TYPES if cyrillic else RO_TYPES)
        if not name or ROUTE_NAME.search(raw) or is_connector(name, places):
            continue
        best = None
        for i in index.get(fold(name), ()):
            d = km(lat, lon, streets[i][4], streets[i][5]) - streets[i][6]
            if d < 2 and (best is None or d < best[0]):
                best = (d, i)
        if best:
            points.append((best[1], number, lat, lon, city))
        else:
            orphans[(street_type or "street", name, bool(cyrillic))].append((lat, lon, number, city))
    grid = defaultdict(list)
    for i, st in enumerate(streets):
        grid[(int(st[4] * 50), int(st[5] * 50))].append(i)
    added = attached = dropped = 0
    for (street_type, name, cyrillic), pts in orphans.items():
        for c in single_link([(p[0], p[1], p) for p in pts], 1.0):
            road = variant_of(name, c["lat"], c["lon"], streets, grid)
            if road is not None:
                # Another spelling of a nearby road ("M. Sadoveanu", "Lermontova"): its buildings belong there.
                attached += 1
                for w in c["ways"]:
                    points.append((road, w[2][2], w[2][0], w[2][1], w[2][3]))
                continue
            if len(c["ways"]) < 2 or re.search(r"(^|\s)\w{1,2}\.", name):
                dropped += 1  # one address, or an abbreviated name that matches no road: too weak to add
                continue
            extent = max(km(c["lat"], c["lon"], w[0], w[1]) for w in c["ways"])
            streets.append([street_type, "" if cyrillic else name, name if cyrillic else "", set(),
                            c["lat"], c["lon"], extent, 0])
            i = len(streets) - 1
            added += 1
            for w in c["ways"]:
                points.append((i, w[2][2], w[2][0], w[2][1], w[2][3]))
    print(f"addresses: {len(points)} points, {added} streets known only from addresses, "
          f"{attached} spelling variants attached to roads, {dropped} dropped")
    return points


ENDINGS = ("ului", "ilor", "elor", "ogo", "ova", "eva", "ina", "oi", "ei", "lor", "ii", "a", "i", "u", "e")


def stem_words(key):
    out = []
    for w in key.split():
        for e in ENDINGS:
            if len(w) >= 5 and w.endswith(e) and len(w) - len(e) >= 4:
                w = w[: -len(e)]
                break
        out.append(w)
    return out


def variant_of(name, lat, lon, streets, grid):
    """The one road within 1 km whose name this is another spelling of, or None. Accepted: the same word
    stems ("Lermontova" / Lermontov), initials of the road's words ("M. Sadoveanu" / Mihail Sadoveanu),
    or a one-letter difference in a long name."""
    key = fold(name)
    words = stem_words(key)
    found = set()
    gi, gj = int(lat * 50), int(lon * 50)
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            for i in grid.get((gi + di, gj + dj), ()):
                st = streets[i]
                if km(lat, lon, st[4], st[5]) - st[6] > 1.0:
                    continue
                for road_name in (st[1], st[2]):
                    if road_name and same_street(words, key, fold(road_name)):
                        found.add(i)
    names = {fold(streets[i][1] or streets[i][2]) for i in found}
    if len(names) != 1:
        return None
    return min(found, key=lambda i: km(lat, lon, streets[i][4], streets[i][5]))


def same_street(words, key, road_key):
    road_words = stem_words(road_key)
    road_last = road_words[-1] if road_words else ""
    if words == road_words:
        return True
    if words and words[-1] == road_last and len(road_last) >= 5:
        # "m sadoveanu", "sadoveanu" for "mihail sadoveanu": every other word an initial or missing
        rest, road_rest = words[:-1], road_words[:-1]
        return len(rest) <= len(road_rest) and all(
            len(w) <= 2 and any(r.startswith(w) for r in road_rest) for w in rest)
    return len(key) >= 8 and levenshtein(key, road_key) <= 1


def levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def street_cities(streets, points):
    """The town most of a street's buildings name in addr:city, when at least 70 % agree."""
    counts = defaultdict(lambda: defaultdict(int))
    for street, _, _, _, city in points:
        if city:
            counts[street][city] += 1
    out = {}
    for street, c in counts.items():
        city, n = max(c.items(), key=lambda kv: kv[1])
        if n >= 0.7 * sum(c.values()):
            out[street] = city
    return out


def write_streets(streets, points):
    cities = street_cities(streets, points)
    order = sorted(range(len(streets)), key=lambda i: (streets[i][1] or streets[i][2], streets[i][0], streets[i][4]))
    position = {old: new for new, old in enumerate(order)}
    with gzip.open(OUT / "md-streets.tsv.gz", "wt", encoding="utf-8", newline="") as f:
        f.write("# Named streets of Moldova. Data © OpenStreetMap contributors, ODbL.\n")
        f.write("type\tname_ro\tname_ru\taliases\tlat\tlon\textent_km\tways\tcity\n")
        for i in order:
            st = streets[i]
            f.write("\t".join([st[0], st[1], st[2], "|".join(sorted(st[3])), f"{st[4]:.6f}", f"{st[5]:.6f}",
                                f"{st[6]:.2f}", str(st[7]), cities.get(i, "")]) + "\n")
    with gzip.open(OUT / "md-addresses.tsv.gz", "wt", encoding="utf-8", newline="") as f:
        f.write("# House numbers of Moldova; street = row of md-streets.tsv.gz (0-based, after the header). "
                "Data © OpenStreetMap contributors, ODbL.\n")
        f.write("street\tnumber\tlat\tlon\n")
        for street, number, lat, lon, _ in sorted(points, key=lambda p: (position[p[0]], p[1])):
            f.write(f"{position[street]}\t{number}\t{lat:.5f}\t{lon:.5f}\n")
    print(f"streets: {len(streets)}")


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
    if len(sys.argv) == 4:  # offline rebuild from previously downloaded files
        places_text, streets_text, addresses_text = (Path(a).read_text(encoding="utf-8") for a in sys.argv[1:4])
    else:
        places_text = fetch(PLACES_QUERY)
        streets_text = fetch(STREETS_QUERY)
        addresses_text = fetch(ADDRESSES_QUERY)
    build_places(places_text)
    places = place_keys(places_text)
    streets = build_streets(streets_text, places)
    points = add_addresses(streets, addresses_text, places)
    write_streets(streets, points)


if __name__ == "__main__":
    main()
