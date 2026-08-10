package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactService;
import com.asterism.identity.SystemAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v5")
public class MemoryController {
    private final MemoryItemRepository memories;
    private final MemoryCandidateRepository candidates;
    private final MemoryCandidateService candidateService;
    private final ArtifactService artifacts;
    private final SystemAccessService access;

    public MemoryController(
            MemoryItemRepository memories,
            MemoryCandidateRepository candidates,
            MemoryCandidateService candidateService,
            ArtifactService artifacts,
            SystemAccessService access) {
        this.memories = memories;
        this.candidates = candidates;
        this.candidateService = candidateService;
        this.artifacts = artifacts;
        this.access = access;
    }

    @GetMapping("/memory")
    List<MemoryView> list(
            @RequestParam String systemId,
            @RequestParam(required = false) MemoryStatus status,
            Authentication actor) {
        access.requireMember(systemId, actor);
        candidateService.refreshSystemArtifactStatuses(systemId);
        candidateService.archiveExpired(systemId);
        var values = status == null
                ? memories.findTop100BySystemIdOrderByCreatedAtDesc(systemId)
                : memories.findTop100BySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
        var targets = candidateService.targetRefs(values);
        return values.stream()
                .map(memory -> view(memory, targets.getOrDefault(memory.memoryId(), List.of())))
                .toList();
    }

    @GetMapping("/memory/candidates")
    List<MemoryCandidateView> candidates(
            @RequestParam String systemId,
            @RequestParam(required = false) MemoryCandidateStatus status,
            Authentication actor) {
        access.requireMember(systemId, actor);
        candidateService.refreshSystemArtifactStatuses(systemId);
        var values = status == null
                ? candidates.findTop100BySystemIdOrderByCreatedAtDesc(systemId)
                : candidates.findTop100BySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
        return values.stream().map(this::view).toList();
    }

    @PostMapping("/memory/candidates/{candidateId}/approve")
    MemoryView approve(
            @PathVariable String candidateId,
            @Valid @RequestBody ApprovalRequest request,
            Authentication actor) {
        var current = requireCandidate(candidateId);
        access.requireOwnerOrAdmin(current.systemId(), actor);
        var memory = candidateService.approve(current, new MemoryCandidateService.CandidateEdit(
                request.memoryType(), request.title(), request.content(), request.confidence(),
                request.applicability(), request.expiresAt(), request.targetRefs()), actor.getName());
        return view(memory, candidateService.targetRefs(List.of(memory))
                .getOrDefault(memory.memoryId(), List.of()));
    }

    @PostMapping("/memory/candidates/{candidateId}/reject")
    MemoryCandidateView reject(
            @PathVariable String candidateId,
            @RequestBody(required = false) RejectRequest request,
            Authentication actor) {
        var current = requireCandidate(candidateId);
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(candidateService.reject(
                current, actor.getName(), request == null ? "" : request.note()));
    }

    @PostMapping("/memory/{memoryId}/archive")
    MemoryView archive(@PathVariable String memoryId, Authentication actor) {
        var current = memories.findById(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("项目记忆不存在"));
        access.requireOwnerOrAdmin(current.systemId(), actor);
        return view(candidateService.archive(current, actor.getName()),
                candidateService.targetRefs(List.of(current)).getOrDefault(memoryId, List.of()));
    }

    private MemoryCandidate requireCandidate(String candidateId) {
        return candidates.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Memory Candidate 不存在"));
    }

    private MemoryCandidateView view(MemoryCandidate candidate) {
        return new MemoryCandidateView(
                candidate.candidateId(),
                candidate.systemId(),
                candidate.projectScope(),
                candidate.memoryType(),
                candidate.artifactSourceId(),
                source(candidate.artifactSourceId()),
                candidate.sourceKind(),
                candidate.title(),
                candidate.content(),
                candidate.confidence(),
                candidate.applicability(),
                candidate.expiresAt(),
                candidate.status(),
                candidateService.targetRefs(candidate),
                candidateService.evidenceRefs(candidate),
                candidate.sourceEventId(),
                candidate.createdBy(),
                candidate.reviewedBy(),
                candidate.reviewNote(),
                candidate.memoryId(),
                candidate.createdAt(),
                candidate.reviewedAt());
    }

    private MemoryView view(MemoryItem memory, List<String> targetRefs) {
        return new MemoryView(
                memory.memoryId(),
                memory.candidateId(),
                memory.systemId(),
                memory.projectScope(),
                memory.memoryType(),
                memory.artifactSourceId(),
                source(memory.artifactSourceId()),
                memory.title(),
                memory.content(),
                memory.confidence(),
                memory.applicability(),
                memory.expiresAt(),
                memory.status(),
                targetRefs,
                candidateService.evidenceRefs(memory),
                memory.sourceEventId(),
                memory.createdBy(),
                memory.approvedBy(),
                memory.createdAt(),
                memory.approvedAt());
    }

    private ArtifactSourceView source(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) return null;
        Artifact artifact = artifacts.require(artifactId);
        return new ArtifactSourceView(
                artifact.artifactId(), artifact.artifactType().name(), artifact.version(),
                artifact.status().name(), artifact.workItemId(), artifact.prdId(),
                artifact.rootArtifactId());
    }

    public record ApprovalRequest(
            @NotNull MemoryType memoryType,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String content,
            @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
            @NotNull MemoryApplicability applicability,
            Instant expiresAt,
            List<String> targetRefs) {
    }

    public record RejectRequest(@Size(max = 500) String note) {
    }

    public record ArtifactSourceView(
            String artifactId,
            String artifactType,
            int version,
            String status,
            String workItemId,
            String prdId,
            String rootArtifactId) {
    }

    public record MemoryCandidateView(
            String candidateId,
            String systemId,
            String projectScope,
            MemoryType memoryType,
            String artifactSourceId,
            ArtifactSourceView artifactSource,
            String sourceKind,
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            Instant expiresAt,
            MemoryCandidateStatus status,
            List<String> targetRefs,
            List<String> evidenceRefs,
            String sourceEventId,
            String createdBy,
            String reviewedBy,
            String reviewNote,
            String memoryId,
            Instant createdAt,
            Instant reviewedAt) {
    }

    public record MemoryView(
            String memoryId,
            String candidateId,
            String systemId,
            String projectScope,
            MemoryType memoryType,
            String artifactSourceId,
            ArtifactSourceView artifactSource,
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            Instant expiresAt,
            MemoryStatus status,
            List<String> targetRefs,
            List<String> evidenceRefs,
            String sourceEventId,
            String createdBy,
            String approvedBy,
            Instant createdAt,
            Instant approvedAt) {
    }
}
