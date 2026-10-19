# Apartment Lens · Scraper service

Collects ads for [Apartment Lens](https://github.com/mcittkmims/apartment-lens) from 999.md and real-estate agency websites and turns them into clean, comparable data. It runs as its own process, so a crash or a block never affects the website.

> **Status:** 999.md (flats, houses and country houses for sale) is collected. Agency websites follow.

## How it works

```
source → source parser → shared normalizer → database
```

Every four hours the service reads every ad of each source. It stores new ads, keeps the original content of an ad whenever it changes, and adds a price snapshot when the price or status changes. Ads missing from a pass are checked one by one and marked gone only when they really are; a pass that looks too short marks nothing gone.

999.md is read through the same JSON API its website uses. Each agency website will get its own small parser.

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

Streets and house numbers come from the cadastre's official register (35,000 streets, 855,000 addresses, the boundaries of every commune), with Russian and old names from OpenStreetMap. They are loaded into the database with `import-ref`, and `export-gazetteer` writes them to the files bundled for tests. The seller's pin decides between similar streets, and an ad without a usable pin is placed on the building of its address. Pins that 999.md fills in by default, shared by many ads on different streets, are not used as locations. When no rule can read a value, the field stays empty and the raw value is recorded with the reason instead of guessed. Every odd value found in practice becomes a unit test.

## Running

The service runs from the main repository with `docker compose up -d`. It can also be run once: `crawl` does one pass, and `renormalize` applies new normalization rules to the stored content without crawling. Tests need Docker: `./gradlew test`.

## Data and technology

Writes the `ingest` schema ([schemas](https://github.com/mcittkmims/apartment-lens/blob/main/database/README.md)); it never decides which ads belong together. Built with Java 25, Spring Boot, `java.net.http` and Jackson. It crawls slowly, one request at a time, and identifies itself in every request.

Street data: Cadastre of the Republic of Moldova (geodata.gov.md); © OpenStreetMap contributors, available under the Open Database License.
