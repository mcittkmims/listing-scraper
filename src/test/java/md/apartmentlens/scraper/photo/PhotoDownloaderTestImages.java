package md.apartmentlens.scraper.photo;

/** Small byte arrays that start like a JPEG file, different for each number. */
public final class PhotoDownloaderTestImages {

    private PhotoDownloaderTestImages() {
    }

    public static byte[] jpeg(int n) {
        byte[] b = new byte[64];
        b[0] = (byte) 0xff;
        b[1] = (byte) 0xd8;
        b[2] = (byte) 0xff;
        b[3] = (byte) 0xe0;
        b[20] = (byte) n;
        return b;
    }
}
