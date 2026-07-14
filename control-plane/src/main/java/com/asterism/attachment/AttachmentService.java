package com.asterism.attachment;

import com.asterism.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private final AttachmentRepository attachments;
    private final JdbcAggregateTemplate aggregate;
    private final StoragePort storage;
    private final ImageSanitizer sanitizer;

    public AttachmentService(AttachmentRepository attachments, JdbcAggregateTemplate aggregate,
                             StoragePort storage, ImageSanitizer sanitizer) {
        this.attachments = attachments;
        this.aggregate = aggregate;
        this.storage = storage;
        this.sanitizer = sanitizer;
    }

    public Attachment upload(String systemId, String uploader, MultipartFile file) {
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "图片不能超过 5MB");
        var declaredType = file.getContentType();
        if (!ALLOWED_TYPES.contains(declaredType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "仅支持 PNG、JPEG、WebP 图片");
        }
        try {
            var sanitized = sanitizer.sanitize(declaredType, file.getBytes());
            var sha256 = sha256(sanitized.content());
            var existing = attachments.findBySystemIdAndSha256(systemId, sha256);
            if (existing.isPresent()) return existing.get();
            var storagePath = storage.save(sha256, sanitized.content());
            var saved = aggregate.insert(new Attachment(
                    "att-" + UUID.randomUUID(), systemId, uploader,
                    file.getOriginalFilename() == null ? "image" : file.getOriginalFilename(),
                    sanitized.contentType(), sanitized.content().length, sha256, storagePath, Instant.now()));
            log.info("图片附件已保存 system={} attachmentId={} size={}", systemId, saved.attachmentId(), saved.sizeBytes());
            return saved;
        } catch (IOException error) {
            throw new IllegalStateException("附件读取失败", error);
        }
    }

    public Attachment require(String attachmentId) {
        return attachments.findById(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在"));
    }

    public Attachment requireForSystem(String attachmentId, String systemId) {
        var attachment = require(attachmentId);
        if (!attachment.systemId().equals(systemId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ATTACHMENT_SYSTEM_MISMATCH", "附件不属于当前系统");
        }
        return attachment;
    }

    public byte[] read(Attachment attachment) {
        return storage.read(attachment.storagePath());
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
