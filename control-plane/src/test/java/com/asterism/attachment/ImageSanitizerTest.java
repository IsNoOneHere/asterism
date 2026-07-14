package com.asterism.attachment;

import com.asterism.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSanitizerTest {
    private final ImageSanitizer sanitizer = new ImageSanitizer();

    @Test
    void rejectsExecutableDisguisedAsPng() {
        assertThatThrownBy(() -> sanitizer.sanitize("image/png", "MZ executable".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("UNSUPPORTED_IMAGE");
    }

    @Test
    void stripsJpegExifSegment() {
        var jpeg = new byte[]{
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xe1, 0x00, 0x0a,
                'E', 'x', 'i', 'f', 0x00, 0x00, 0x01, 0x02,
                (byte) 0xff, (byte) 0xda, 0x00, 0x02,
                (byte) 0xff, (byte) 0xd9,
        };

        var clean = sanitizer.sanitize("image/jpeg", jpeg).content();

        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(clean).startsWith((byte) 0xff, (byte) 0xd8).endsWith((byte) 0xff, (byte) 0xd9);
    }
}
