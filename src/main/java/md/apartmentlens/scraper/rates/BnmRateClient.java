package md.apartmentlens.scraper.rates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Official exchange rates of the National Bank of Moldova (bnm.md), published daily as XML. */
public class BnmRateClient {

    static final String URL = "https://www.bnm.md/en/official_exchange_rates?get_xml=1&date=";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final PoliteHttpClient http;

    public BnmRateClient(PoliteHttpClient http) {
        this.http = http;
    }

    /** Lei per one unit of each currency, as published for the date. */
    public Map<String, BigDecimal> rates(LocalDate date) throws IOException {
        PoliteHttpClient.Response r = http.get(URI.create(URL + DATE.format(date)), Map.of("Accept", "application/xml"));
        return parse(r.body());
    }

    static Map<String, BigDecimal> parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList valutes = doc.getElementsByTagName("Valute");
            Map<String, BigDecimal> out = new HashMap<>();
            for (int i = 0; i < valutes.getLength(); i++) {
                Element v = (Element) valutes.item(i);
                String code = text(v, "CharCode");
                BigDecimal value = new BigDecimal(text(v, "Value"));
                BigDecimal nominal = new BigDecimal(text(v, "Nominal"));
                out.put(code, value.divide(nominal, 4, RoundingMode.HALF_UP));
            }
            if (!out.containsKey("EUR") || !out.containsKey("USD")) {
                throw new IOException("BNM response without EUR and USD rates");
            }
            return out;
        } catch (ParserConfigurationException | SAXException | NumberFormatException e) {
            throw new IOException("unreadable BNM response", e);
        }
    }

    private static String text(Element e, String tag) {
        return e.getElementsByTagName(tag).item(0).getTextContent().strip();
    }
}
