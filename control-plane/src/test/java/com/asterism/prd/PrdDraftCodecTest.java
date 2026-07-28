package com.asterism.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdDraftCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrdDraftCodec codec = new PrdDraftCodec(objectMapper);

    @Test
    void readsLegacyDraftAndKeepsUnknownTopLevelFields() throws Exception {
        var json = """
                {"title":"登录页","goal":"增加错误提示","scope":"code_change",
                 "acceptanceCriteria":["错误密码时提示"],
                 "suspectedTargets":[{"entryId":"page-1","kind":"page","title":"登录页","routePath":"/login",
                   "apiEndpoints":["POST /api/login"],"codeRefs":["src/login.tsx"],"confidence":0.9}],
                 "legacyFlag":{"enabled":true}}
                """;

        var draft = codec.read(json);
        var roundTrip = objectMapper.readValue(codec.write(draft), Map.class);

        assertThat(draft.acceptanceCriteria()).containsExactly("错误密码时提示");
        assertThat(draft.suspectedTargets()).singleElement().satisfies(target -> {
            assertThat(target.entryId()).isEqualTo("page-1");
            assertThat(target.apiEndpoints()).containsExactly("POST /api/login");
        });
        assertThat(roundTrip).containsEntry("legacyFlag", Map.of("enabled", true));
        assertThat(roundTrip).doesNotContainKey("extras");
    }

    @Test
    void reportsAcceptanceCriteriaFieldAndActualType() {
        assertThatThrownBy(() -> codec.fromMap(Map.of(
                "title", "登录页",
                "goal", "修复错误提示",
                "acceptanceCriteria", Map.of("text", "显示中文错误")
        )))
                .isInstanceOf(PrdDraftCodec.DraftFieldTypeException.class)
                .hasMessageContaining("acceptanceCriteria", "List<String>", "java.util");
    }

    @Test
    void productContentExposesOnlySemanticFields() throws Exception {
        var draft = codec.read("""
                {"title":"登录页","goal":"增加错误提示","scope":"code_change",
                 "acceptanceCriteria":["错误密码时提示"],
                 "targets":[{"entryId":"page-1","kind":"page","title":"登录页","confidence":1.0}],
                 "citations":{"goal":["MEM:rule-1"]}}
                """);

        var json = objectMapper.writeValueAsString(draft.productContent());

        assertThat(json).contains("title", "goal", "scope", "acceptanceCriteria");
        assertThat(json).doesNotContain("targets", "citations", "extras");
    }
}
