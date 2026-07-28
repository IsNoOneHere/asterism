package com.asterism.prd;

import com.asterism.context.ContextItem;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;

@Component
@Profile("!llm")
public class FakeProductAgentAdapter implements ProductAgentPort {
    @Override
    public DraftResult updateDraft(String systemId, String content, PrdContent currentDraft, List<String> missingFields,
                                   List<ConversationMessage> conversationHistory, List<ContextItem> contextItems) {
        // fake agent 锁定 PRD 流程边界：没有验收标准就追问，避免引入真实 LLM 变量。
        var hadAcceptance = !currentDraft.acceptanceCriteria().isEmpty();
        var answersAcceptance = missingFields.contains("acceptance_criteria");
        var hasAcceptance = hadAcceptance || answersAcceptance || content.contains("验收") || content.toLowerCase().contains("acceptance");
        var title = currentDraft.title() == null
                ? "PRD-" + Math.abs(String.valueOf(currentDraft.goal() == null ? content : currentDraft.goal()).hashCode())
                : currentDraft.title();
        var goal = currentDraft.goal() == null ? content : currentDraft.goal();
        List<String> acceptance = hasAcceptance ? List.of(content) : List.of();
        var patch = new PrdPatch(title, goal, "code_change", acceptance);
        var message = hasAcceptance ? "PRD draft 已就绪，请确认。" : "还缺验收标准，请补充。";
        var source = contextItems.stream().filter(item -> "user_message".equals(item.type()))
                .map(ContextItem::refId).reduce((first, second) -> second).stream().toList();
        var citations = hasAcceptance
                ? Map.of("goal", source, "AC-1", source)
                : Map.of("goal", source);
        return new DraftResult(patch, message, citations);
    }

    @Override
    public MemoryCandidateResult extractMemoryCandidates(
            String systemId, PrdContent draft, List<String> targetRefs, List<ContextItem> contextItems) {
        return new MemoryCandidateResult(List.of());
    }
}
