package com.asterism.prd;

import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.MemoryCandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrdMemoryCandidateServiceTest {
    @Test
    void confirmedPrdCreatesOnlyCandidatesBackedByFinalEvidenceAndTargets() {
        var candidates = mock(MemoryCandidateService.class);
        var service = new PrdMemoryCandidateService(candidates, new PrdCitationService(), new ObjectMapper());
        var extras = new LinkedHashMap<String, Object>();
        extras.put("citations", Map.of("RULE-1", List.of("MEM:rule-1")));
        extras.put("memoryCandidates", List.of(
                Map.of("category", "constraint", "audience", "product", "title", "发布权限",
                        "content", "只有负责人可以确认发布", "target_refs", List.of("page-release", "other"),
                        "evidence_refs", List.of("MEM:rule-1", "MEM:fabricated")),
                Map.of("category", "lesson", "audience", "execution", "title", "无依据候选",
                        "content", "不要保存", "target_refs", List.of(), "evidence_refs", List.of("MEM:fabricated"))));
        var draft = new PrdDraft("发布流程", "明确发布权限", "code_change", List.of("负责人可确认"),
                List.of(), List.of(new KnowledgeMatchService.SuspectedTarget(
                "page-release", "page", "发布页", "/release", List.of(), List.of(), 1.0)), extras);
        var now = Instant.now();
        var session = new PrdSession("prd-1", "sys-1", "conv-1", null, null, "发布流程", "明确发布权限",
                "{}", "[]", "waiting_user_confirm", "requester", null, null, now, now);

        service.createCandidates(session, draft, "wi-1", "requester");

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(List.class);
        verify(candidates).createAll(captor.capture());
        assertThat((List<MemoryCandidateService.CandidateInput>) captor.getValue()).singleElement().satisfies(input -> {
            assertThat(input.sourceRef()).startsWith("prd:prd-1:");
            assertThat(input.targetRefs()).containsExactly("page-release");
            assertThat(input.evidenceRefs()).containsExactly("MEM:rule-1");
            assertThat(input.audience()).isEqualTo("product");
        });
    }
}
