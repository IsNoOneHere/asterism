package com.agentteam.v5.memory;

import com.agentteam.v5.event.DomainEventService;
import com.agentteam.v5.event.DomainEventType;
import com.agentteam.v5.identity.SystemAccessService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v5")
public class MemoryController {
    private final MemoryItemRepository memories;
    private final DomainEventService events;
    private final SystemAccessService access;
    private final ContextManifestService manifests;
    private final JdbcAggregateTemplate aggregate;

    public MemoryController(MemoryItemRepository memories, DomainEventService events, SystemAccessService access,
                            ContextManifestService manifests, JdbcAggregateTemplate aggregate) {
        this.memories = memories;
        this.events = events;
        this.access = access;
        this.manifests = manifests;
        this.aggregate = aggregate;
    }

    @GetMapping("/memory")
    Iterable<MemoryItem> list(@RequestParam String systemId, @RequestParam(required = false) String status,
                              Authentication actor) {
        access.requireMember(systemId, actor);
        return status == null || status.isBlank()
                ? memories.findTop100BySystemIdOrderByCreatedAtDesc(systemId)
                : memories.findTop100BySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
    }

    @PostMapping("/memory/candidates")
    MemoryItem candidate(@RequestBody CandidateRequest request, Authentication actor) {
        access.requireMember(request.systemId(), actor);
        var now = Instant.now();
        var memory = new MemoryItem("mem-" + UUID.randomUUID(), request.systemId(), request.content(),
                "candidate", request.sourceEventId(), null, "{}", actor.getName(), now, null);
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
        return memory;
    }

    @PostMapping("/memory/{memoryId}/approve")
    MemoryItem approve(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        var approved = new MemoryItem(current.memoryId(), current.systemId(), current.content(), "approved",
                current.sourceEventId(), actor.getName(), current.metadataJson(), current.createdBy(),
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
        return approved;
    }

    @PostMapping("/memory/{memoryId}/reject")
    MemoryItem reject(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return changeStatus(current, "rejected", DomainEventType.MemoryRejected, actor.getName());
    }

    @PostMapping("/memory/{memoryId}/disable")
    MemoryItem disable(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId).orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return changeStatus(current, "disabled", DomainEventType.MemoryDisabled, actor.getName());
    }

    @GetMapping("/context-snapshots")
    ContextSnapshot snapshot(@RequestParam String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        // GET 只做 UI 预览，不写 manifest；审计只允许 worker POST 通道产生。
        var approved = memories.findBySystemIdAndStatus(systemId, "approved");
        return new ContextSnapshot(systemId, null, approved);
    }

    @PostMapping("/context-snapshots")
    ContextSnapshot workerSnapshot(@RequestBody SnapshotRequest request) {
        // worker 回调通道由 token filter 保护；这里仍只返回 approved 记忆。
        var approved = memories.findBySystemIdAndStatus(request.systemId(), "approved");
        var manifestId = manifests.create(request.systemId(), request.workItemId(), approved);
        return new ContextSnapshot(request.systemId(), manifestId, approved);
    }

    private MemoryItem changeStatus(MemoryItem current, String status, DomainEventType eventType, String actorId) {
        // reject/disable 不删除原始内容，只变更治理状态，避免上下文再召回。
        var changed = new MemoryItem(current.memoryId(), current.systemId(), current.content(), status,
                current.sourceEventId(), current.approvedBy(), current.metadataJson(), current.createdBy(),
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

    public record CandidateRequest(@NotBlank String systemId, @NotBlank String content, String sourceEventId) {
    }

    public record ContextSnapshot(String systemId, @Schema(nullable = true) String manifestId, Iterable<MemoryItem> approvedMemories) {
    }

    public record SnapshotRequest(@NotBlank String systemId, String workItemId) {
    }
}
