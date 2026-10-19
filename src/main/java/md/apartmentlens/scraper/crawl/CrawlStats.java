package md.apartmentlens.scraper.crawl;

/** Counts for one crawl run; mutable, used by one thread. */
public final class CrawlStats {

    private int advertised = -1;
    private long requests;
    private int seen;
    private int added;
    private int changed;
    private int priceChanges;
    private int gone;
    private int errors;

    void advertised(int n) {
        advertised = n;
    }

    void requests(long n) {
        requests = n;
    }

    void countSeen() {
        seen++;
    }

    void countAdded() {
        added++;
    }

    void countChanged() {
        changed++;
    }

    void countPriceChange() {
        priceChanges++;
    }

    void countGone() {
        gone++;
    }

    void countError() {
        errors++;
    }

    public int advertised() {
        return advertised;
    }

    public long requests() {
        return requests;
    }

    public int seen() {
        return seen;
    }

    public int added() {
        return added;
    }

    public int changed() {
        return changed;
    }

    public int priceChanges() {
        return priceChanges;
    }

    public int gone() {
        return gone;
    }

    public int errors() {
        return errors;
    }

    @Override
    public String toString() {
        return "advertised " + advertised + ", seen " + seen + ", new " + added + ", changed " + changed
                + ", price changes " + priceChanges + ", gone " + gone + ", errors " + errors + ", requests " + requests;
    }
}
