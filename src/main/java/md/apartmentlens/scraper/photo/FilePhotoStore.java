package md.apartmentlens.scraper.photo;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Photos as files in a folder: {@code 3f/a2/3fa2…c9.jpg}, named by the SHA-256 of their content. Written to a
 * temporary file first and moved into place, so a file under its final name is always complete.
 */
public class FilePhotoStore implements PhotoStore {

    private final Path root;

    public FilePhotoStore(Path root) {
        this.root = root;
    }

    @Override
    public void save(byte[] sha256, String extension, byte[] content) throws IOException {
        Path target = path(sha256, extension);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".part-", null);
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The file of a photo in this folder. */
    public Path path(byte[] sha256, String extension) {
        return root.resolve(PhotoStore.name(sha256, extension));
    }
}
