package com.asterism.attachment;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/internal/attachments")
public class InternalAttachmentController {
    private final AttachmentService attachments;

    public InternalAttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<byte[]> download(@PathVariable String attachmentId, @RequestParam String systemId) {
        // Worker 必须同时证明附件 ID 与系统归属，避免跨系统读取图片内容。
        var attachment = attachments.requireForSystem(attachmentId, systemId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .body(attachments.read(attachment));
    }
}
