package edu.gatech.sip6321.social;

import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

public record ValidatedImage(byte[] bytes, String mediaType, String extension) {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    // Operational decoder bounds, not a database field or product feature.
    static final long MAX_PIXELS = 16_000_000;
    static final int MAX_DIMENSION = 8192;

    static ValidatedImage read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw SocialFailure.image();
        if (file.getSize() > MAX_BYTES) throw SocialFailure.large();
        try (var stream = file.getInputStream()) {
            byte[] data = stream.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw SocialFailure.large();
            boolean png =
                    data.length >= 8
                            && Arrays.equals(
                                    Arrays.copyOf(data, 8),
                                    new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            boolean jpeg =
                    data.length >= 3
                            && data[0] == (byte) 255
                            && data[1] == (byte) 216
                            && data[2] == (byte) 255;
            if (!png && !jpeg) throw SocialFailure.image();
            String type = png ? "image/png" : "image/jpeg";
            if (!type.equalsIgnoreCase(file.getContentType())) throw SocialFailure.image();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw SocialFailure.image();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    var warning = new java.util.concurrent.atomic.AtomicBoolean();
                    reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1
                            || height < 1
                            || width > MAX_DIMENSION
                            || height > MAX_DIMENSION
                            || (long) width * height > MAX_PIXELS) throw SocialFailure.image();
                    if (reader.read(0) == null || warning.get()) throw SocialFailure.image();
                } finally {
                    reader.dispose();
                }
            }
            return new ValidatedImage(data, type, png ? "png" : "jpg");
        } catch (IOException | IllegalArgumentException e) {
            throw SocialFailure.image();
        }
    }

    @Override
    public String toString() {
        return "ValidatedImage[redacted]";
    }
}
