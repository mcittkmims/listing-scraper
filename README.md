# Apartment Lens · Scraper service

Collects ads for [Apartment Lens](https://github.com/mcittkmims/apartment-lens) from 999.md and real-estate agency websites and turns them into clean, comparable data. It runs as its own process, so a crash or a block never affects the website.

> **Status:** flats and houses for sale are collected from 999.md, CasaMare and the agencies Proimobil, OkeyImobil, Realist and Romanescu.

## How it works

```
source → source parser → shared normalizer → database
```

Every four hours the service reads every ad of each source. It stores new ads, keeps the original content of an ad whenever it changes, and adds a price snapshot when the price or status changes. Ads missing from a pass are checked one by one and marked gone only when they really are; a pass that looks too short marks nothing gone.

Each source has its own small parser; everything after it is shared.

| Source | Read through | Notes |
|---|---|---|
| 999.md | The JSON API of its website | Flats, houses and country houses; the seller's pin |
| CasaMare | The JSON API of its website | Private sellers and agents; a summary per ad, the ad when it changes and at least every six days, so its photo links (signed for seven) stay valid |
| Proimobil | The JSON API of its website | Names its copy of each ad on 999.md; sold ads and properties in Romania are left out |
| OkeyImobil | The JSON API of its website | Names the 999.md ad each property came from |
| Realist | Its HTML pages and their structured data | Pins the site marks as fuzzy only place an ad roughly |
| Romanescu | Its HTML pages and their structured data | Approximate pins (a circle of a few hundred metres) |

Carpatics is not collected: almost all of its ads are Proimobil's. Prime Imobil lists no flats or houses.

After each pass the service downloads the photos it has not stored yet, the newest ads first and every photo server at its own polite pace. Each photo is downloaded once per site and stored once per content, as a file named by its SHA-256 (`photos` downloads the backlog without crawling).

Where a site lists only a summary of each ad, the whole ad is read when the summary changes, and at least once a week. Private data a site returns but does not publish (an owner's phone, agents' notes) is removed before anything is stored.

## Normalization

| Field | Raw examples | Result |
|---|---|---|
| Price | "99 990 €", "1 200 000 lei", "60 de mii" | Amount and currency, plus EUR at that day's BNM rate |
| Rooms | "Apartament cu 2 camere", "2-комнатная", "garsonieră" | A number, and whether a living room is included |
| Area | "67", "54 mp", "6 ari" | m², taken from the description when it is more precise ("66,38 m²") |
| Floor | "7" of "9", "3/9", "Demisol", "Mansardă" | Floor 7 of 9; semi-basement 0, attic and penthouse marked |
| Street | "ул. Каля Ешилор", "str. Calea Iesilor", "M.Sadoveanu" | The official name: Calea Ieșilor, Mihail Sadoveanu |
| House number | "57/a", "9 a", "1\8", "-", "***" | "57/A", "9A", "1/8", or empty when hidden |
| Location | The seller's pin, or the street and house number | A map point and how precise it is: building, street, district or locality |
| Phone | "069 123 456", "+373 69123456" | A keyed hash of `+37369123456` |

Streets and house numbers come from the cadastre's official register (35,000 streets, 855,000 addresses, the boundaries of every commune), with Russian and old names from OpenStreetMap. The address-data tool of the main repository (`database/geodata`) loads them into the database; the scraper only reads them, and `export-gazetteer` writes them to the files bundled for tests. The seller's pin decides between similar streets, and an ad without a usable pin is placed on the building of its address. Pins that 999.md fills in by default, shared by many ads on different streets, are not used as locations. When no rule can read a value, the field stays empty and the raw value is recorded with the reason instead of guessed. Every odd value found in practice becomes a unit test.

## Running

The service runs from the main repository with `docker compose up -d`. It can also be run once: `crawl` does one pass, and `renormalize` applies new normalization rules to the stored content without crawling; both take source names (`crawl proimobil`). Tests need Docker: `./gradlew test`.

## Data and technology

Writes the `ingest` schema ([schemas](https://github.com/mcittkmims/apartment-lens/blob/main/database/README.md)); it never decides which ads belong together. Built with Java 25, Spring Boot, `java.net.http` and Jackson. It crawls slowly, one request at a time, and identifies itself in every request.

Street data: Cadastre of the Republic of Moldova (geodata.gov.md); © OpenStreetMap contributors, available under the Open Database License.
