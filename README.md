# Apartment Lens · Scraper service

The scraper service collects ads for [Apartment Lens](https://github.com/mcittkmims/apartment-lens) from 999.md and from real-estate agency websites and turns them into clean, comparable data. It runs as its own process on a schedule, so a crash or a blocked request never affects the website.

> **Status:** design stage. This README describes the plan; there is no code yet.

## Sources

999.md is the main source and covers most of the Moldovan market, so it gets the first parser. Each agency website gets its own small parser. Agency websites list fewer properties, but many of their listings also appear on 999.md, and connecting those duplicates is exactly what the matching in the core service does.

## Crawl process

Every few hours the service visits each source and passes each ad through the same pipeline:

```
source page → source parser → shared normalizer → database
```

The source parser knows only the layout of its own website and extracts raw values such as the price text or the floor text. Before writing an HTML parser for a new source, check the browser's network tab: many websites load their data as JSON, which is more stable to parse than HTML.

The shared normalizer converts those raw values into the same fields for every source. The service then stores the ad in `ingest.listing` and adds a row to `ingest.listing_snapshot` only when the price or status has changed. It also saves the raw page in compressed form, so that normalization can be re-run when a rule improves without crawling again.

Only photo URLs that the service has not seen before are downloaded. Each photo file is stored under its SHA-256 checksum, registered in `media.image`, and linked to its ad through `media.listing_image`. For every new photo the service adds a fingerprint job to the queue for the online vision worker. When an ad disappears from its source, the service marks it as gone.

## Normalization

Ads are written in Romanian and Russian, in many formats, so normalization is the largest part of this service.

| Field | Raw examples | Normalized value |
|---|---|---|
| Price | "60 000 €", "1 200 000 lei", "60 de mii", "Preț negociabil" | Amount and currency, a EUR value at that day's BNM rate, and a negotiable flag |
| Rooms | "2 camere", "2-комнатная", "garsonieră" | A number |
| Area | "54 m²", "54 mp", "54 кв.м" | Total area in m², flagged when only the living area is given |
| Floor | "3/9", "etajul 3 din 9", "3 этаж из 9" | Floor 3 of 9 |
| Address | "str. Mircea cel Bătrân", "ул. Мирча чел Бэтрын" | One spelling, based on a Romanian–Russian alias table and consistent diacritics |
| Location | The ad's map pin, or only an address | A map point taken from the pin or from the geocoder, with its precision |
| Building and finish | "seria 143", "bloc nou", "variantă albă", "евроремонт" | A value from a fixed list |
| Phone | "069 123 456", "+373 69123456" | `+37369123456`, stored only as a keyed hash |

The normalizer never guesses. When no rule can read a value, the field stays empty and the raw text is logged, and the most frequent failures show which pattern to support next. Every unusual value found in practice becomes a JUnit test case, so the rules can change without breaking what already works.

## Crawling etiquette

The service follows each website's terms and robots.txt, crawls slowly from a single IP address, and never downloads a page or photo it already has. 999.md is the most important source, so losing access to it would hurt the project more than a slower crawl.

## Data access

The service writes to the `ingest` schema, adds photo rows to `media`, and adds jobs to `ops`. It never writes to `catalog`, because deciding which ads belong together is the core service's job.

## Technology

The service uses Java 21 or later with `java.net.http`, jsoup for HTML and Jackson for JSON. Playwright for Java is used only for websites that require a real browser. Addresses are geocoded with a self-hosted Nominatim instance.
