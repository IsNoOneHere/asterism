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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v5")
public class MemoryController {
    private final MemoryItemRepository memories;
    private final DomainEventService events;
    private final SystemAccessService access;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;

    public MemoryController(MemoryItemRepository memories, DomainEventService events, SystemAccessService access,
                            JdbcAggregateTemplate aggregate, ObjectMapper objectMapper) {
        this.memories = memories;
        this.events = events;
        this.access = access;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/memory")
    List<MemoryView> list(@RequestParam String systemId, @RequestParam(required = false) String status,
                          Authentication actor) {
        access.requireMember(systemId, actor);
        var values = status == null || status.isBlank()
                ? memories.findTop100BySystemIdOrderByCreatedAtDesc(systemId)
                : memories.findTop100BySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
        return values.stream().map(this::view).toList();
    }

    @PostMapping("/memory/candidates")
    MemoryView candidate(@Valid @RequestBody MemoryCandidateRequest request, Authentication actor) {
        access.requireMember(request.systemId(), actor);
        var now = Instant.now();
        var memory = new MemoryItem("mem-" + UUID.randomUUID(), request.systemId(), request.content(),
                "candidate", audience(request.audience()), null, null,
                metadata(request.category(), request.title(), request.workItemId()),
                actor.getName(), now, null);
        aggregate.insert(memory);
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.MemoryCandidateCreated,
                request.systemId(),
                null,
                null,
                null,
                actor.getName(),
                "control-plane",
                Map.of("memoryId", memory.memoryId()),
                memory.memoryId(),
                null,
                "memory-candidate:" + memory.memoryId()));
        return view(memory);
    }

    @PostMapping("/memory/{memoryId}/approve")
    MemoryView approve(@PathVariable String memoryId,
                       @Valid @RequestBody(required = false) ApprovalRequest request,
                       Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        var approved = new MemoryItem(current.memoryId(), current.systemId(),
                request == null ? current.content() : request.content(), "approved",
                request == null ? current.audience() : audience(request.audience()),
                current.sourceEventId(), actor.getName(), request == null ? current.metadataJson()
                : metadata(request.category(), request.title(), workItemId(current)), current.createdBy(),
                current.createdAt(), Instant.now());
        aggregate.update(approved);
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.MemoryApproved,
                approved.systemId(),
                null,
                null,
                null,
                actor.getName(),
                "control-plane",
                Map.of("memoryId", memoryId),
                memoryId,
                null,
                "memory-approved:" + memoryId));
        return view(approved);
    }

    @PostMapping("/memory/{memoryId}/reject")
    MemoryView reject(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(changeStatus(current, "rejected", DomainEventType.MemoryRejected, actor.getName()));
    }

    @PostMapping("/memory/{memoryId}/disable")
    MemoryView disable(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(changeStatus(current, "disabled", DomainEventType.MemoryDisabled, actor.getName()));
    }

    private MemoryItem changeStatus(MemoryItem current, String status, DomainEventType eventType, String actorId) {
        // reject/disable 不删除原始内容，只变更治理状态，避免上下文再召回。
        var changed = new MemoryItem(current.memoryId(), current.systemId(), current.content(), status,
                current.audience(), current.sourceEventId(), current.approvedBy(), current.metadataJson(), current.createdBy(),
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

    private MemoryView view(MemoryItem memory) {
        var metadata = readMetadata(memory.metadataJson());
        var title = text(metadata.get("title"));
        if (title.isBlank()) title = fallbackTitle(memory.content());
        return new MemoryView(memory.memoryId(), memory.systemId(), text(metadata.get("category")), title,
                memory.content(), memory.status(), memory.audience(), workItemId(memory), memory.sourceEventId(), memory.approvedBy(),
                memory.metadataJson(), memory.createdBy(), memory.createdAt(), memory.approvedAt());
    }

    private String audience(String value) {
        return value == null || value.isBlank() ? "both" : value;
    }

    private String metadata(String category, String title, String workItemId) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("category", category);
        metadata.put("title", title);
        if (workItemId != null && !workItemId.isBlank()) metadata.put("workItemId", workItemId);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("记忆元数据不是合法 JSON", error);
        }
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
            String workItemId) {
    }

    public record ApprovalRequest(
            @NotBlank @Pattern(regexp = "constraint|convention|lesson") String category,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String content,
            @Pattern(regexp = "product|execution|both") String audience) {
    }

    public record MemoryView(String memoryId, String systemId, String category, String title, String content,
                             String status, String audience, String workItemId, String sourceEventId, String approvedBy,
                             String metadataJson, String createdBy, Instant createdAt, Instant approvedAt) {
    }

}
