package md.apartmentlens.scraper.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import md.apartmentlens.scraper.store.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoStoreTest {

    @TempDir
    Path dir;

    @Test
    void aPhotoIsStoredOnceUnderTheHashOfItsContent() throws Exception {
        FilePhotoStore store = new FilePhotoStore(dir);
        byte[] content = PhotoDownloaderTestImages.jpeg(1);
        byte[] sha = Bytes.sha256(content);
        store.save(sha, "jpg", content);
        store.save(sha, "jpg", content);

        Path file = store.path(sha, "jpg");
        assertThat(Files.readAllBytes(file)).isEqualTo(content);
        assertThat(dir.relativize(file).toString()).isEqualTo(PhotoStore.name(sha, "jpg"));
        assertThat(PhotoStore.name(sha, "jpg")).matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}\\.jpg");
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1); // no temporary file left
        }
    }

    @Test
    void imagesAreRecognizedByTheirFirstBytes() {
        assertThat(PhotoDownloader.ImageType.of(PhotoDownloaderTestImages.jpeg(1))).isEqualTo(PhotoDownloader.ImageType.JPEG);
        assertThat(PhotoDownloader.ImageType.of("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo(PhotoDownloader.ImageType.WEBP);
        assertThat(PhotoDownloader.ImageType.of("<html>Not Found</html>".getBytes())).isNull();
    }
}
