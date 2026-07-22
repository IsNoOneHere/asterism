package com.asterism.memory;

import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v5")
public class MemoryController {
    private final MemoryItemRepository memories;
    private final DomainEventService events;
    private final SystemAccessService access;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;
    private final MemoryCandidateService candidates;

    public MemoryController(MemoryItemRepository memories, DomainEventService events, SystemAccessService access,
                            JdbcAggregateTemplate aggregate, ObjectMapper objectMapper,
                            MemoryCandidateService candidates) {
        this.memories = memories;
        this.events = events;
        this.access = access;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
        this.candidates = candidates;
    }

    @GetMapping("/memory")
    List<MemoryView> list(@RequestParam String systemId, @RequestParam(required = false) String status,
                          Authentication actor) {
        access.requireMember(systemId, actor);
        var values = status == null || status.isBlank()
                ? memories.findTop100BySystemIdOrderByCreatedAtDesc(systemId)
                : memories.findTop100BySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
        var targets = candidates.targetRefs(values);
        return values.stream().map(memory -> view(memory, targets.getOrDefault(memory.memoryId(), List.of()))).toList();
    }

    @PostMapping("/memory/candidates")
    MemoryView candidate(@Valid @RequestBody MemoryCandidateRequest request, Authentication actor) {
        access.requireMember(request.systemId(), actor);
        var memory = candidates.create(new MemoryCandidateService.CandidateInput(
                request.systemId(), request.category(), request.audience(), request.title(), request.content(), "",
                request.targetRefs(), List.of(), request.workItemId(), "", actor.getName()));
        return view(memory, candidates.targetRefs(List.of(memory)).getOrDefault(memory.memoryId(), List.of()));
    }

    @PostMapping("/memory/{memoryId}/approve")
    MemoryView approve(@PathVariable String memoryId,
                       @Valid @RequestBody(required = false) ApprovalRequest request,
                       Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        var currentTargets = candidates.targetRefs(List.of(current)).getOrDefault(memoryId, List.of());
        var approved = candidates.approve(current, request == null
                ? new MemoryCandidateService.CandidateEdit(
                category(current), current.audience(), title(current), current.content(), currentTargets)
                : new MemoryCandidateService.CandidateEdit(
                request.category(), request.audience(), request.title(), request.content(), request.targetRefs()),
                actor.getName());
        return view(approved, candidates.targetRefs(List.of(approved)).getOrDefault(memoryId, List.of()));
    }

    @PostMapping("/memory/{memoryId}/reject")
    MemoryView reject(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(changeStatus(current, "rejected", DomainEventType.MemoryRejected, actor.getName()),
                candidates.targetRefs(List.of(current)).getOrDefault(memoryId, List.of()));
    }

    @PostMapping("/memory/{memoryId}/disable")
    MemoryView disable(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(changeStatus(current, "disabled", DomainEventType.MemoryDisabled, actor.getName()),
                candidates.targetRefs(List.of(current)).getOrDefault(memoryId, List.of()));
    }

    private MemoryItem changeStatus(MemoryItem current, String status, DomainEventType eventType, String actorId) {
        // reject/disable 不删除原始内容，只变更治理状态，避免上下文再召回。
        var changed = new MemoryItem(current.memoryId(), current.systemId(), current.content(), status,
                current.audience(), current.stableCandidateId(), current.sourceRef(), current.evidenceRefs(),
                current.normalizedContentHash(), current.sourceEventId(), current.approvedBy(),
                current.metadataJson(), current.createdBy(),
                current.createdAt(), current.approvedAt());
        aggregate.update(changed);
        events.append(new DomainEventService.AppendEvent(
                eventType,
                changed.systemId(),
                null,
                null,
                null,
                actorId,
                "control-plane",
                Map.of("memoryId", changed.memoryId()),
                changed.memoryId(),
                null,
                "memory-" + status + ":" + changed.memoryId()));
        return changed;
    }

    private MemoryView view(MemoryItem memory, List<String> targetRefs) {
        var metadata = readMetadata(memory.metadataJson());
        var title = text(metadata.get("title"));
        if (title.isBlank()) title = fallbackTitle(memory.content());
        return new MemoryView(memory.memoryId(), memory.systemId(), text(metadata.get("category")), title,
                memory.content(), memory.status(), memory.audience(), memory.stableCandidateId(), memory.sourceRef(),
                targetRefs, candidates.evidenceRefs(memory), workItemId(memory), memory.sourceEventId(), memory.approvedBy(),
                memory.metadataJson(), memory.createdBy(), memory.createdAt(), memory.approvedAt());
    }

    private String category(MemoryItem memory) {
        return text(readMetadata(memory.metadataJson()).get("category"));
    }

    private String title(MemoryItem memory) {
        var value = text(readMetadata(memory.metadataJson()).get("title"));
        return value.isBlank() ? fallbackTitle(memory.content()) : value;
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private String workItemId(MemoryItem memory) {
        return text(readMetadata(memory.metadataJson()).get("workItemId"));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String fallbackTitle(String content) {
        var title = content.lines().findFirst().orElse("").trim();
        return title.length() <= 80 ? title : title.substring(0, 80);
    }

    public record MemoryCandidateRequest(
            @NotBlank String systemId,
            @NotBlank @Pattern(regexp = "constraint|convention|lesson") String category,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String content,
            @Pattern(regexp = "product|execution|both") String audience,
            List<String> targetRefs,
            String workItemId) {
    }

    public record ApprovalRequest(
            @NotBlank @Pattern(regexp = "constraint|convention|lesson") String category,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String content,
            @Pattern(regexp = "product|execution|both") String audience,
            List<String> targetRefs) {
    }

    public record MemoryView(String memoryId, String systemId, String category, String title, String content,
                             String status, String audience, String stableCandidateId, String sourceRef,
                             List<String> targetRefs, List<String> evidenceRefs,
                             String workItemId, String sourceEventId, String approvedBy,
                             String metadataJson, String createdBy, Instant createdAt, Instant approvedAt) {
    }

}
