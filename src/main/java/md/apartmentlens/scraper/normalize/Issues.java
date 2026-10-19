package md.apartmentlens.scraper.normalize;

import java.util.ArrayList;
import java.util.List;

/** Collects the issues found while normalizing one ad. */
public final class Issues {

    private final List<Issue> list = new ArrayList<>();

    public void add(String field, Object raw, String reason) {
        list.add(new Issue(field, raw == null ? null : String.valueOf(raw), reason));
    }

    public List<Issue> list() {
        return List.copyOf(list);
    }

    public boolean has(String field) {
        return list.stream().anyMatch(i -> i.field().equals(field));
    }
}
