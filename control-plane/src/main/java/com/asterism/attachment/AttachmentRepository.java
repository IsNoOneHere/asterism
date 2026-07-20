package com.asterism.attachment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends CrudRepository<Attachment, String> {
    Optional<Attachment> findBySystemIdAndSha256(String systemId, String sha256);

    @Query("""
            select distinct attachment.*
            from attachments attachment
            join conversation_messages message on message.prd_id = :prdId
            join lateral jsonb_array_elements_text(message.attachment_ids) reference(attachment_id)
                on reference.attachment_id = attachment.attachment_id
            where attachment.system_id = :systemId
            order by attachment.created_at, attachment.attachment_id
            """)
    List<Attachment> findByPrdIdAndSystemId(String prdId, String systemId);
}
