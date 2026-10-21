package md.apartmentlens.scraper.text;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/** Text of the HTML descriptions some sources publish: paragraphs and line breaks kept, markup dropped. */
public final class Html {

    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "ul", "ol", "h1", "h2",
            "h3", "h4", "h5", "h6", "tr", "table", "blockquote", "section", "article");

    private Html() {
    }

    /** The text of an HTML fragment, one line per paragraph or line break; null for empty text. */
    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        StringBuilder out = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    out.append(t.getWholeText().replace(' ', ' '));
                } else if (node instanceof Element e && (e.normalName().equals("br") || BLOCKS.contains(e.normalName()))) {
                    out.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    out.append('\n');
                }
            }
        });
        return Text.cleanMultiline(out.toString());
    }
}
