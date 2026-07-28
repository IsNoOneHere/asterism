package com.asterism.prd;

import com.asterism.context.ContextHash;
import com.asterism.context.ContextItem;
import com.asterism.memory.MemoryCandidateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PrdMemoryCandidateService {
    private static final Logger log = LoggerFactory.getLogger(PrdMemoryCandidateService.class);

    private final ProductAgentPort productAgent;
    private final MemoryCandidateService candidates;
    private final PrdCitationService citations;
    private final TaskExecutor taskExecutor;

    public PrdMemoryCandidateService(ProductAgentPort productAgent, MemoryCandidateService candidates,
                                     PrdCitationService citations, TaskExecutor taskExecutor) {
        this.productAgent = productAgent;
        this.candidates = candidates;
        this.citations = citations;
        this.taskExecutor = taskExecutor;
    }

    public void extractAsync(
            PrdSession session,
            PrdDraft draft,
            String workItemId,
            String actorId,
            List<ContextItem> contextItems) {
        try {
            taskExecutor.execute(() -> extract(session, draft, workItemId, actorId, contextItems));
        } catch (RuntimeException error) {
            log.warn("PRD 记忆候选任务提交失败 prdId={} type={}",
                    session.prdId(), error.getClass().getSimpleName());
        }
    }

    void extract(
            PrdSession session,
            PrdDraft draft,
            String workItemId,
            String actorId,
            List<ContextItem> contextItems) {
        try {
            var targetRefs = draft.targets().stream()
                    .map(com.asterism.knowledge.KnowledgeMatchService.SuspectedTarget::entryId)
                    .toList();
            var result = productAgent.extractMemoryCandidates(
                    session.systemId(), draft.productContent(), targetRefs, contextItems);
            var created = candidates.createAll(inputs(
                    session, draft, workItemId, actorId, contextItems, result.candidates()));
            log.info("PRD 记忆候选提取完成 prdId={} proposed={} created={}",
                    session.prdId(), result.candidates().size(), created.size());
        } catch (RuntimeException error) {
            // 记忆沉淀是确认后的附加流程，失败不能回滚 PRD 或阻断工作项创建。
            log.warn("PRD 记忆候选提取失败 prdId={} type={}",
                    session.prdId(), error.getClass().getSimpleName());
        }
    }

    List<MemoryCandidateService.CandidateInput> inputs(
            PrdSession session,
            PrdDraft draft,
            String workItemId,
            String actorId,
            List<ContextItem> contextItems,
            List<ProductAgentPort.MemoryCandidateProposal> proposals) {
        var recalledRefs = contextItems.stream().map(ContextItem::refId).collect(Collectors.toSet());
        var allowedEvidence = new LinkedHashSet<>(citations.references(draft));
        allowedEvidence.retainAll(recalledRefs);
        var allowedTargets = draft.targets().stream()
                .map(com.asterism.knowledge.KnowledgeMatchService.SuspectedTarget::entryId)
                .collect(Collectors.toSet());
        var result = new ArrayList<MemoryCandidateService.CandidateInput>();
        for (var proposal : proposals) {
            var evidence = proposal.evidenceRefs().stream().filter(allowedEvidence::contains).toList();
            if (evidence.isEmpty()) continue;
            var targets = proposal.targetRefs().stream().filter(allowedTargets::contains).toList();
            var hash = ContextHash.sha256(String.valueOf(proposal.content()));
            result.add(new MemoryCandidateService.CandidateInput(
                    session.systemId(), proposal.category(), proposal.audience(), proposal.title(), proposal.content(),
                    "prd:" + session.prdId() + ":" + hash.substring(0, 16), targets, evidence,
                    workItemId, "", actorId));
        }
        return List.copyOf(result);
    }
}
