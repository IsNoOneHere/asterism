package com.asterism.attachment;

import com.asterism.identity.SystemAccessService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v5/attachments")
public class AttachmentController {
    private final AttachmentService attachments;
    private final SystemAccessService access;

    public AttachmentController(AttachmentService attachments, SystemAccessService access) {
        this.attachments = attachments;
        this.access = access;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Attachment upload(@RequestParam String systemId, @RequestParam MultipartFile file, Authentication actor) {
        access.requireMember(systemId, actor);
        return attachments.upload(systemId, actor.getName(), file);
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<byte[]> download(@PathVariable String attachmentId, Authentication actor) {
        var attachment = attachments.require(attachmentId);
        access.requireMember(attachment.systemId(), actor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(attachment.filename(), StandardCharsets.UTF_8).build().toString())
                .body(attachments.read(attachment));
    }
}
