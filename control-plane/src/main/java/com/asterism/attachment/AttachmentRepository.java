package com.asterism.attachment;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AttachmentRepository extends CrudRepository<Attachment, String> {
    Optional<Attachment> findBySystemIdAndSha256(String systemId, String sha256);
}
