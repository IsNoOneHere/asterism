package com.asterism.prd;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;

@Component
@Profile("!llm")
public class FakeProductAgentAdapter implements ProductAgentPort {
    @Override
    public DraftResult updateDraft(String systemId, String content, Map<String, Object> currentDraft, List<String> missingFields,
                                   List<ConversationMessage> conversationHistory, List<String> approvedMemories) {
        // fake agent 锁定 PRD 流程边界：没有验收标准就追问，避免引入真实 LLM 变量。
        var previousAcceptance = currentDraft.get("acceptanceCriteria");
        var hadAcceptance = previousAcceptance instanceof List<?> list && !list.isEmpty();
        var answersAcceptance = missingFields.contains("acceptance_criteria");
        var hasAcceptance = hadAcceptance || answersAcceptance || content.contains("验收") || content.toLowerCase().contains("acceptance");
        var missing = hasAcceptance ? List.<String>of() : List.of("acceptance_criteria");
        var goal = currentDraft.getOrDefault("goal", content);
        var acceptance = hasAcceptance ? List.of(content) : List.of();
        var draft = Map.<String, Object>of(
                "goal", goal,
                "scope", "code_change",
                "acceptanceCriteria", acceptance);
        var message = missing.isEmpty() ? "PRD draft 已就绪，请确认。" : "还缺验收标准，请补充。";
        return new DraftResult((String) currentDraft.getOrDefault("title", "PRD-" + Math.abs(String.valueOf(goal).hashCode())),
                draft, missing, message);
    }
}
