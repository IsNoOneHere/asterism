package com.asterism.prd;

import com.asterism.context.ContextHash;
import com.asterism.context.ContextItem;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.MemoryCandidateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrdMemoryCandidateServiceTest {
    @Test
    void confirmedPrdCreatesOnlyCandidatesBackedByFinalEvidenceAndTargets() {
        var productAgent = mock(ProductAgentPort.class);
        var candidates = mock(MemoryCandidateService.class);
        var service = new PrdMemoryCandidateService(
                productAgent, candidates, new PrdCitationService(), Runnable::run);
        var extras = new LinkedHashMap<String, Object>();
        extras.put("citations", Map.of("goal", List.of("MEM:rule-1")));
        var draft = new PrdDraft("发布流程", "明确发布权限", "code_change", List.of("负责人可确认"),
                List.of(), List.of(new KnowledgeMatchService.SuspectedTarget(
                "page-release", "page", "发布页", "/release", List.of(), List.of(), 1.0)), extras);
        var now = Instant.now();
        var session = new PrdSession("prd-1", "sys-1", "conv-1", null, null, "发布流程", "明确发布权限",
                "{}", "[]", "waiting_user_confirm", "requester", null, null, now, now);
        var contextItems = List.of(new ContextItem(
                "MEM:rule-1", "memory", "product", "发布权限", "负责人批准后才能发布",
                List.of("page-release"), "manual", ContextHash.sha256("负责人批准后才能发布"), 1.0));
        when(productAgent.extractMemoryCandidates(anyString(), any(), anyList(), anyList()))
                .thenReturn(new ProductAgentPort.MemoryCandidateResult(List.of(
                        new ProductAgentPort.MemoryCandidateProposal(
                                "constraint", "product", "发布权限", "只有负责人可以确认发布",
                                List.of("page-release", "other"), List.of("MEM:rule-1", "MEM:fabricated")),
                        new ProductAgentPort.MemoryCandidateProposal(
                                "lesson", "execution", "无依据候选", "不要保存",
                                List.of(), List.of("MEM:fabricated")))));
        when(candidates.createAll(anyList())).thenReturn(List.of());

        service.extract(session, draft, "wi-1", "requester", contextItems);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(List.class);
        verify(candidates).createAll(captor.capture());
        assertThat((List<MemoryCandidateService.CandidateInput>) captor.getValue()).singleElement().satisfies(input -> {
            assertThat(input.sourceRef()).startsWith("prd:prd-1:");
            assertThat(input.targetRefs()).containsExactly("page-release");
            assertThat(input.evidenceRefs()).containsExactly("MEM:rule-1");
            assertThat(input.audience()).isEqualTo("product");
        });
        verify(productAgent).extractMemoryCandidates(
                "sys-1", draft.productContent(), List.of("page-release"), contextItems);
    }

    @Test
    void modelFailureDoesNotBlockConfirmedPrd() {
        var productAgent = mock(ProductAgentPort.class);
        var candidates = mock(MemoryCandidateService.class);
        var service = new PrdMemoryCandidateService(
                productAgent, candidates, new PrdCitationService(), Runnable::run);
        var now = Instant.now();
        var session = new PrdSession("prd-1", "sys-1", "conv-1", "wi-1", "case-1", "标题", "目标",
                "{}", "[]", "case_starting", "requester", "requester", now, now, now);
        var draft = new PrdDraft("标题", "目标", "code_change", List.of("验收"),
                List.of(), List.of(), Map.of());
        when(productAgent.extractMemoryCandidates(anyString(), any(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("model down"));

        assertThatCode(() -> service.extract(session, draft, "wi-1", "requester", List.of()))
                .doesNotThrowAnyException();
        verifyNoInteractions(candidates);
    }
}
