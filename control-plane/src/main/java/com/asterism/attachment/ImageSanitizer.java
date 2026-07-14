package com.asterism.attachment;

import com.asterism.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class ImageSanitizer {
    public SanitizedImage sanitize(String declaredType, byte[] content) {
        var actualType = detect(content);
        if (!actualType.equals(declaredType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_MISMATCH", "图片真实类型与 Content-Type 不一致");
        }
        var clean = switch (actualType) {
            case "image/jpeg" -> stripJpegExif(content);
            case "image/png" -> stripPngExif(content);
            case "image/webp" -> stripWebpExif(content);
            default -> throw new IllegalStateException("不支持的图片类型");
        };
        return new SanitizedImage(actualType, clean);
    }

    private String detect(byte[] bytes) {
        if (startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (bytes.length >= 12 && text(bytes, 0, 4).equals("RIFF") && text(bytes, 8, 4).equals("WEBP")) return "image/webp";
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "仅支持 PNG、JPEG、WebP 图片");
    }

    private byte[] stripJpegExif(byte[] bytes) {
        var output = new ByteArrayOutputStream(bytes.length);
        output.write(bytes[0]);
        output.write(bytes[1]);
        var offset = 2;
        while (offset < bytes.length) {
            var markerStart = offset;
            if ((bytes[offset] & 0xff) != 0xff) throw invalidImage();
            while (offset < bytes.length && (bytes[offset] & 0xff) == 0xff) offset++;
            if (offset >= bytes.length) throw invalidImage();
            var marker = bytes[offset++] & 0xff;
            if (marker == 0xda || marker == 0xd9) {
                output.write(bytes, markerStart, bytes.length - markerStart);
                return output.toByteArray();
            }
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) {
                output.write(bytes, markerStart, offset - markerStart);
                continue;
            }
            if (offset + 2 > bytes.length) throw invalidImage();
            var length = unsignedShort(bytes, offset);
            var end = offset + length;
            if (length < 2 || end > bytes.length) throw invalidImage();
            var exif = marker == 0xe1 && end >= offset + 8 && text(bytes, offset + 2, 6).equals("Exif\0\0");
            if (!exif) output.write(bytes, markerStart, end - markerStart);
            offset = end;
        }
        throw invalidImage();
    }

    private byte[] stripPngExif(byte[] bytes) {
        var output = new ByteArrayOutputStream(bytes.length);
        output.write(bytes, 0, 8);
        var offset = 8;
        var ended = false;
        while (offset + 12 <= bytes.length) {
            var length = intBigEndian(bytes, offset);
            var end = offset + 12L + length;
            if (length < 0 || end > bytes.length) throw invalidImage();
            var type = text(bytes, offset + 4, 4);
            if (!"eXIf".equals(type)) output.write(bytes, offset, (int) end - offset);
            offset = (int) end;
            if ("IEND".equals(type)) {
                ended = true;
                break;
            }
        }
        if (!ended || offset != bytes.length) throw invalidImage();
        return output.toByteArray();
    }

    private byte[] stripWebpExif(byte[] bytes) {
        var output = new ByteArrayOutputStream(bytes.length);
        output.write(bytes, 0, 12);
        var offset = 12;
        while (offset + 8 <= bytes.length) {
            var length = intLittleEndian(bytes, offset + 4);
            var paddedLength = length + (length & 1);
            var end = offset + 8L + paddedLength;
            if (length < 0 || end > bytes.length) throw invalidImage();
            var type = text(bytes, offset, 4);
            if (!"EXIF".equals(type)) {
                var chunk = Arrays.copyOfRange(bytes, offset, (int) end);
                if ("VP8X".equals(type) && length > 0) chunk[8] = (byte) (chunk[8] & ~0x08);
                output.writeBytes(chunk);
            }
            offset = (int) end;
        }
        if (offset != bytes.length) throw invalidImage();
        var clean = output.toByteArray();
        writeLittleEndian(clean, 4, clean.length - 8);
        return clean;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private String text(byte[] value, int offset, int length) {
        if (offset + length > value.length) return "";
        return new String(value, offset, length, StandardCharsets.ISO_8859_1);
    }

    private int unsignedShort(byte[] value, int offset) {
        return (value[offset] & 0xff) << 8 | value[offset + 1] & 0xff;
    }

    private int intBigEndian(byte[] value, int offset) {
        return (value[offset] & 0xff) << 24 | (value[offset + 1] & 0xff) << 16
                | (value[offset + 2] & 0xff) << 8 | value[offset + 3] & 0xff;
    }

    private int intLittleEndian(byte[] value, int offset) {
        return value[offset] & 0xff | (value[offset + 1] & 0xff) << 8
                | (value[offset + 2] & 0xff) << 16 | (value[offset + 3] & 0xff) << 24;
    }

    private void writeLittleEndian(byte[] value, int offset, int number) {
        for (var index = 0; index < 4; index++) value[offset + index] = (byte) (number >>> index * 8);
    }

    private ApiException invalidImage() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "图片内容损坏");
    }

    public record SanitizedImage(String contentType, byte[] content) {
    }
}
