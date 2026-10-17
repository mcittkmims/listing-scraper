# Apartment Lens · Scraper

The scraper of [Apartment Lens](https://github.com/mcittkmims/apartment-lens). It collects ads from Moldovan listing sites and turns them into clean, comparable data. It runs as its own process, so a crash or a block never takes the website down.

> **Status:** design stage. This README describes the plan; there is no code yet.

## What it does

- Crawls 999.md and makler.md every few hours, later also lalafo.md and agency sites.
- Normalizes every ad into the same fields, whatever the site.
- Saves the ad, a price snapshot when price or status changed, the raw page, and any new photos.
- Marks ads as gone when they disappear from a site.

## How a crawl works

```
site page → site parser → shared normalizer → database
```

1. **A parser per site** reads only that site's layout and pulls out raw values. Check the browser's Network tab first: if a site loads its data as JSON, parse that instead of the HTML.
2. **The shared normalizer** turns raw values into clean fields, the same way for every site.
3. **Saving:**
   - The ad goes into `ingest.listing`.
   - A snapshot goes into `ingest.listing_snapshot`, only if price or status changed.
   - The raw page is saved gzipped, so normalization can be re-run later.
4. **Photos:** only photo URLs not seen before are downloaded.
   - Each file is stored under its SHA-256.
   - It gets a row in `media.image` and is linked to the ad in `media.listing_image`.
   - New photos get a `fingerprint` job for the vision service.

## Normalization

| Field | Examples | Becomes |
|---|---|---|
| Price | "60 000 €", "1 200 000 lei", "60 de mii", "Preț negociabil" | Amount + currency + EUR at that day's BNM rate + a negotiable flag |
| Rooms | "2 camere", "2-комнатная", "garsonieră" | A number |
| Area | "54 m²", "54 mp", "54 кв.м" | Total m², flagged if only living area is given |
| Floor | "3/9", "etajul 3 din 9", "3 этаж из 9" | Floor 3 of 9 |
| Address | "str. Mircea cel Bătrân", "ул. Мирча чел Бэтрын" | One spelling, using a RO/RU alias table and the same diacritics |
| Location | Site's map pin, or address only | A map point: the pin, or geocoded with Nominatim |
| Building / finish | "seria 143", "bloc nou", "variantă albă", "евроремонт" | A fixed list of values |
| Phone | "069 123 456", "+373 69123456" | `+37369123456`, then hashed; the number itself is never stored |

- **Never guess.** A value no rule can read stays empty, and the raw text is logged. The most frequent failures show which pattern to add next.
- **Test with real values.** Every odd value found becomes a JUnit test case.

## Crawling politely

- Read each site's terms and robots.txt.
- Crawl slowly, from one IP, with caching.
- Never re-download a photo or page you already have.

## Database

- **Writes:** `ingest`, photo rows in `media`, jobs in `ops`.
- **Never** touches `catalog`; it doesn't decide anything about flats.

## Runs on

The old laptop, on a schedule.

## Tech

- Java 21 or 25
- `java.net.http`, jsoup (HTML), Jackson (JSON)
- Playwright for Java, only for sites that need a real browser
- JUnit
- Nominatim (self-hosted geocoder)
