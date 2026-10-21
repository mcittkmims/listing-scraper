package md.apartmentlens.scraper.photo;

import java.io.IOException;
import java.util.HexFormat;

/** Where photo files are kept, each under the hash of its content. */
public interface PhotoStore {

    /** Saves the file unless one with this hash is already there. */
    void save(byte[] sha256, String extension, byte[] content) throws IOException;

    /** A photo's name in the store: "3f/a2/3fa2…c9.jpg". */
    static String name(byte[] sha256, String extension) {
        String hex = HexFormat.of().formatHex(sha256);
        return hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex + "." + extension;
    }
}
