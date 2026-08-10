package com.asterism.artifact;

import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ArtifactController {
    private final ArtifactService artifacts;
    private final ArtifactVersionSelectionService versionSelection;
    private final WorkItemProjectionRepository workItems;
    private final SystemAccessService access;

    public ArtifactController(ArtifactService artifacts,
                              ArtifactVersionSelectionService versionSelection,
                              WorkItemProjectionRepository workItems,
                              SystemAccessService access) {
        this.artifacts = artifacts;
        this.versionSelection = versionSelection;
        this.workItems = workItems;
        this.access = access;
    }

    @GetMapping("/api/v5/artifacts/{artifactId}")
    ArtifactDetailView detail(@PathVariable String artifactId, Authentication actor) {
        var artifact = artifacts.require(artifactId);
        access.requireMember(artifact.systemId(), actor);
        return detailView(artifact);
    }

    @GetMapping("/api/v5/artifacts")
    List<ArtifactSummaryView> byPrd(@RequestParam String prdId,
                                    @RequestParam(defaultValue = "PRODUCT") ArtifactType type,
                                    Authentication actor) {
        var result = artifacts.findByPrd(prdId, type);
        if (!result.isEmpty()) access.requireMember(result.getFirst().systemId(), actor);
        return result.stream().map(this::summaryView).toList();
    }

    @GetMapping("/api/v5/artifacts/{artifactId}/ancestors")
    List<ArtifactSummaryView> ancestors(@PathVariable String artifactId, Authentication actor) {
        var artifact = artifacts.require(artifactId);
        access.requireMember(artifact.systemId(), actor);
        return artifacts.findAncestors(artifactId).stream().map(this::summaryView).toList();
    }

    @GetMapping("/api/v5/artifacts/{artifactId}/versions")
    List<ArtifactSummaryView> versions(@PathVariable String artifactId, Authentication actor) {
        var artifact = artifacts.require(artifactId);
        access.requireMember(artifact.systemId(), actor);
        return artifacts.findVersionHistory(artifactId).stream().map(this::summaryView).toList();
    }

    @GetMapping("/api/v5/work-items/{workItemId}/artifacts")
    ArtifactGraphView graph(@PathVariable String workItemId, Authentication actor) {
        var item = resolve(workItemId);
        access.requireMember(item.systemId(), actor);
        var graph = artifacts.graph(item.workItemId());
        return new ArtifactGraphView(
                graph.rootArtifactId(),
                graph.nodes().stream().map(this::summaryView).toList(),
                graph.edges(),
                graph.effectiveHeads(),
                versionSelection.versionActions(item, graph.nodes(), access.canControl(item.systemId(), actor)));
    }

    @PostMapping("/api/v5/work-items/{workItemId}/artifacts/active")
    ArtifactVersionSelectionService.SelectionResponse selectVersion(
            @PathVariable String workItemId,
            @RequestBody ArtifactVersionSelectionService.SelectionRequest request,
            Authentication actor) {
        var item = resolve(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        return versionSelection.select(item, request, actor.getName());
    }

    @PostMapping("/api/v5/work-items/{workItemId}/artifacts/continue")
    ArtifactVersionSelectionService.SelectionResponse continueExecution(
            @PathVariable String workItemId,
            @RequestBody ArtifactVersionSelectionService.SelectionRequest request,
            Authentication actor) {
        var item = resolve(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        return versionSelection.continueExecution(item, request, actor.getName());
    }

    private ArtifactDetailView detailView(Artifact artifact) {
        return new ArtifactDetailView(
                summaryView(artifact),
                artifacts.transitions(artifact.artifactId()).stream()
                        .map(value -> new TransitionView(
                                value.transitionId(), value.fromStatus(), value.toStatus(), value.actor(),
                                value.note(), value.domainEventId(), value.createdAt()))
                        .toList(),
                artifacts.evidence(artifact.artifactId()).stream()
                        .map(value -> new EvidenceView(
                                value.evidenceId(), value.evidenceType(), value.payload(), value.transitionId(),
                                value.domainEventId(), value.actor(), value.createdAt()))
                        .toList());
    }

    private ArtifactSummaryView summaryView(Artifact artifact) {
        return new ArtifactSummaryView(
                ArtifactRef.from(artifact), artifact.systemId(), artifact.prdId(), artifact.workItemId(),
                artifact.caseId(), artifact.content(), artifact.createdBy(), artifact.createdAt(),
                artifact.reviewedBy(), artifact.reviewedAt(), artifact.reviewNote());
    }

    private WorkItemProjection resolve(String workItemId) {
        return workItems.findById(workItemId)
                .or(() -> workItems.findByDisplayWorkItemId(workItemId))
                .filter(item -> !item.deleted())
                .orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
    }

    public record ArtifactSummaryView(
            ArtifactRef ref,
            String systemId,
            String prdId,
            String workItemId,
            String caseId,
            JsonNode content,
            String createdBy,
            Instant createdAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewNote) {
    }

    public record TransitionView(
            String transitionId,
            ArtifactStatus fromStatus,
            ArtifactStatus toStatus,
            String actor,
            String note,
            String domainEventId,
            Instant createdAt) {
    }

    public record EvidenceView(
            String evidenceId,
            String evidenceType,
            JsonNode payload,
            String transitionId,
            String domainEventId,
            String actor,
            Instant createdAt) {
    }

    public record ArtifactDetailView(
            ArtifactSummaryView artifact,
            List<TransitionView> transitions,
            List<EvidenceView> evidence) {
    }

    public record ArtifactGraphView(
            String rootArtifactId,
            List<ArtifactSummaryView> nodes,
            List<ArtifactGraph.Edge> edges,
            Map<ArtifactType, ArtifactRef> effectiveHeads,
            Map<String, ArtifactVersionSelectionService.VersionActionAvailability> versionActions) {
    }
}
