# Apartment Lens · Scraper service

Collects ads for [Apartment Lens](https://github.com/mcittkmims/apartment-lens) from 999.md and real-estate agency websites and turns them into clean, comparable data. It runs as its own process, so a crash or a block never affects the website.

> **Status:** design stage. There is no code yet.

## How it works

```
source page → source parser → shared normalizer → database
```

Every few hours, each source's parser extracts the raw values from its ads, and the shared normalizer converts them into the same fields for every source. The service stores the ad, adds a price snapshot when the price or status changed, and downloads photos it has not seen before. Each new photo gets a fingerprint job for the vision worker.

999.md has one parser, and each agency website gets its own small one. Before writing an HTML parser, check whether the site loads its data as JSON; that is far more stable to parse.

## Normalization

| Field | Raw examples | Result |
|---|---|---|
| Price | "60 000 €", "1 200 000 lei", "60 de mii" | Amount, currency and EUR value at that day's BNM rate |
| Rooms | "2 camere", "2-комнатная", "garsonieră" | A number |
| Area | "54 m²", "54 mp", "54 кв.м" | Total area in m² |
| Floor | "3/9", "etajul 3 din 9", "3 этаж из 9" | Floor 3 of 9 |
| Address | "str. Mircea cel Bătrân", "ул. Мирча чел Бэтрын" | One spelling, from a Romanian–Russian alias table |
| Location | Map pin, or address only | A map point, from the pin or the geocoder |
| Phone | "069 123 456", "+373 69123456" | A keyed hash of `+37369123456` |

When no rule can read a value, the field stays empty and the raw text is logged instead of guessed. Every odd value found in practice becomes a unit test.

## Data and technology

Writes `ingest`, photo rows in `media`, and jobs in `ops` ([schemas](https://github.com/mcittkmims/apartment-lens/blob/main/database/README.md)); it never decides which ads belong together. Built with Java 21+, `java.net.http`, jsoup and Jackson, with Playwright for sites that need a real browser and a self-hosted Nominatim geocoder. It crawls slowly and respects each site's terms.
