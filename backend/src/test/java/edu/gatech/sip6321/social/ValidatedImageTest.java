package edu.gatech.sip6321.social;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ValidatedImageTest {
    static byte[] bytes(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }

    static MockMultipartFile file(String format) throws Exception {
        return new MockMultipartFile(
                "image",
                "../../ignored." + format,
                "image/" + (format.equals("jpg") ? "jpeg" : format),
                bytes(format));
    }

    @Test
    void acceptsJpegPngAndInclusiveLimit() throws Exception {
        assertThat(ValidatedImage.read(file("jpg")).extension()).isEqualTo("jpg");
        assertThat(ValidatedImage.read(file("png")).extension()).isEqualTo("png");
        byte[] padded = Arrays.copyOf(bytes("png"), ValidatedImage.MAX_BYTES);
        assertThat(
                        ValidatedImage.read(
                                        new MockMultipartFile(
                                                "image", "x.png", "image/png", padded))
                                .bytes())
                .hasSize(ValidatedImage.MAX_BYTES);
    }

    @Test
    void rejectsOversizeEmptyUnsupportedCorruptAndMismatch() throws Exception {
        assertThatThrownBy(
                        () ->
                                ValidatedImage.read(
                                        new MockMultipartFile(
                                                "image", new byte[ValidatedImage.MAX_BYTES + 1])))
                .isInstanceOf(SocialFailure.class)
                .extracting("status")
                .isEqualTo(413);
        for (var file :
                java.util.List.of(
                        new MockMultipartFile("image", new byte[0]),
                        new MockMultipartFile("image", "x.gif", "image/gif", bytes("gif")),
                        new MockMultipartFile(
                                "image",
                                "x.png",
                                "image/png",
                                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}),
                        new MockMultipartFile("image", "x.jpg", "image/jpeg", bytes("png")))) {
            assertThatThrownBy(() -> ValidatedImage.read(file))
                    .isInstanceOf(SocialFailure.class)
                    .extracting("status")
                    .isEqualTo(415);
        }
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecoding() throws Exception {
        byte[] png = bytes("png");
        // IHDR width at offset 16; no allocation for the claimed image dimensions.
        png[16] = 0x7f;
        png[17] = (byte) 255;
        png[18] = (byte) 255;
        png[19] = (byte) 255;
        assertThatThrownBy(
                        () ->
                                ValidatedImage.read(
                                        new MockMultipartFile("image", "x.png", "image/png", png)))
                .isInstanceOf(SocialFailure.class);
    }

    @Test
    void textRejectsInvalidUnicodeAndTreatsUnicodeSpacesAsEmpty() {
        assertThat(SocialText.meaningful("\u00a0\u2003\t")).isFalse();
        for (String text : java.util.List.of("\0", "\ud800", "\udc00"))
            assertThatThrownBy(() -> SocialText.validate(text)).isInstanceOf(SocialFailure.class);
        assertThat(SocialText.validate("🌱 volunteering")).isEqualTo("🌱 volunteering");
    }
}
