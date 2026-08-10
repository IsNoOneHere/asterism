package com.asterism.attachment;

import com.asterism.common.ApiException;
import com.asterism.identity.SecurityConfig;
import com.asterism.identity.WorkerCallbackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalAttachmentController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(WorkerCallbackProperties.class)
@TestPropertySource(properties = "asterism.worker-callback.token=test-worker-token")
class InternalAttachmentControllerTest {
    private static final byte[] IMAGE = "private-image-bytes".getBytes();

    @Autowired MockMvc mockMvc;
    @MockBean AttachmentService attachments;

    @Test
    void rejectsRequestWithoutWorkerToken() throws Exception {
        mockMvc.perform(get("/api/v5/internal/attachments/att-1").param("systemId", "sys-1"))
                .andExpect(status().isUnauthorized());

        verify(attachments, never()).requireForSystem("att-1", "sys-1");
    }

    @Test
    void returnsOwnedAttachmentWithOriginalContentType() throws Exception {
        var attachment = attachment();
        when(attachments.requireForSystem("att-1", "sys-1")).thenReturn(attachment);
        when(attachments.read(attachment)).thenReturn(IMAGE);

        mockMvc.perform(get("/api/v5/internal/attachments/att-1")
                        .param("systemId", "sys-1")
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(IMAGE));
    }

    @Test
    void wrongSystemDoesNotReadOrLeakAttachmentBytes() throws Exception {
        when(attachments.requireForSystem("att-1", "sys-other")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "ATTACHMENT_SYSTEM_MISMATCH", "附件不属于当前系统"));

        mockMvc.perform(get("/api/v5/internal/attachments/att-1")
                        .param("systemId", "sys-other")
                        .header("X-Agent-Team-Worker-Token", "test-worker-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("private-image-bytes"))));

        verify(attachments, never()).read(attachment());
    }

    @Test
    void missingAttachmentDoesNotLeakContent() throws Exception {
        when(attachments.requireForSystem("att-missing", "sys-1")).thenThrow(new ApiException(
                HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在"));

        mockMvc.perform(get("/api/v5/internal/attachments/att-missing")
                        .param("systemId", "sys-1")
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("private-image-bytes"))));
    }

    private Attachment attachment() {
        return new Attachment(
                "att-1", "sys-1", "user-1", "screen.png", "image/png",
                IMAGE.length, "sha", "sha/path", Instant.parse("2026-08-04T00:00:00Z"));
    }
}
