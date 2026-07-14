package com.asterism.attachment;

import com.asterism.IntegrationDatabase;
import com.asterism.identity.JdbcUserAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "asterism.storage.root=${java.io.tmpdir}/asterism-attachment-tests")
@AutoConfigureMockMvc
class AttachmentIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcUserAccountService users;

    private String outsider;

    @BeforeEach
    void createOutsider() {
        outsider = "outsider-" + UUID.randomUUID();
        users.upsertUser(outsider, "Outsider", outsider + "@local", "secret-one");
    }

    @Test
    void uploadDeduplicatesAndDownloadRequiresMembership() throws Exception {
        var first = upload(png());
        var second = upload(png());
        assertThat(first).isEqualTo(second);

        mockMvc.perform(get("/api/v5/attachments/" + first).with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v5/attachments/" + first).with(httpBasic(outsider, "secret-one")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadRejectsFakePngMagicBytes() throws Exception {
        mockMvc.perform(multipart("/api/v5/attachments")
                        .file(new MockMultipartFile("file", "attack.png", "image/png", "MZ executable".getBytes()))
                        .param("systemId", "demo-system")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isUnsupportedMediaType());
    }

    private String upload(byte[] bytes) throws Exception {
        var body = mockMvc.perform(multipart("/api/v5/attachments")
                        .file(new MockMultipartFile("file", "screen.png", "image/png", bytes))
                        .param("systemId", "demo-system")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("attachmentId").asText();
    }

    private byte[] png() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0, 0, 0, 0, 'I', 'E', 'N', 'D', 0, 0, 0, 0,
        };
    }
}
