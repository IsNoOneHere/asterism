package com.asterism.artifact;

import com.asterism.context.ContextItem;
import com.asterism.context.RequirementContextManifestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ArtifactContextBuilder {
    private final ArtifactService artifacts;
    private final RequirementContextManifestService manifests;
    private final ObjectMapper objectMapper;

    public ArtifactContextBuilder(ArtifactService artifacts, RequirementContextManifestService manifests,
                                  ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.manifests = manifests;
        this.objectMapper = objectMapper;
    }

    public ArtifactContextSnapshot build(Request request) {
        var product = artifacts.requireEffectiveApproved(request.productArtifact());
        requireScope(request, product);
        if (product.artifactType() != ArtifactType.PRODUCT || product.parentArtifactId() != null) {
            throw new IllegalStateException("Planning 的业务输入必须是有效 Approved ProductArtifact");
        }
        var manifestId = product.content().path("requirementManifestId").asText();
        if (!manifestId.equals(request.requirementManifestId())) {
            throw new IllegalStateException("ProductArtifact 与 RequirementContextManifest 不一致");
        }
        Artifact planning = null;
        if ("coding".equals(request.phase())) {
            planning = artifacts.requireEffectiveApproved(request.planningArtifact());
            requireScope(request, planning);
            if (planning.artifactType() != ArtifactType.PLANNING
                    || !product.artifactId().equals(planning.parentArtifactId())) {
                throw new IllegalStateException("Coding 必须使用当前 Product 祖先下的有效 Approved PlanningArtifact");
            }
        } else if (!"planning".equals(request.phase())) {
            throw new IllegalArgumentException("不支持的 Artifact Context 阶段: " + request.phase());
        }
        var previous = previous(request, product, planning);
        var productDraft = objectMapper.convertValue(product.content(), new TypeReference<Map<String, Object>>() {});
        var artifactSourceIds = new ArrayList<String>();
        artifactSourceIds.add(product.artifactId());
        if (planning != null) artifactSourceIds.add(planning.artifactId());
        if (previous != null) artifactSourceIds.add(previous.artifactId());
        var execution = manifests.executionSnapshot(
                request.systemId(), request.prdId(), request.workItemId(), manifestId,
                request.phase(), product.content().path("goal").asText(), productDraft,
                List.copyOf(artifactSourceIds));
        var stale = new ArrayList<>(execution.staleReferences());
        var gitBases = Map.copyOf(request.gitBaseRevisions() == null ? Map.of() : request.gitBaseRevisions());
        if (planning != null) {
            var plannedBases = objectMapper.convertValue(
                    planning.content().path("baseRevisions"), new TypeReference<Map<String, String>>() {});
            for (var entry : plannedBases.entrySet()) {
                var actual = gitBases.get(entry.getKey());
                if (!Objects.equals(actual, entry.getValue())) {
                    stale.add("git:" + entry.getKey() + ":" + entry.getValue()
                            + "->" + (actual == null ? "missing" : actual));
                }
            }
        }
        var sourceArtifacts = new ArrayList<ArtifactRef>();
        sourceArtifacts.add(ArtifactRef.from(product));
        if (planning != null) sourceArtifacts.add(ArtifactRef.from(planning));
        if (previous != null) sourceArtifacts.add(ArtifactRef.from(previous));
        var feedbackNotes = feedbackNotes(product, planning, previous);
        var relationships = sourceArtifacts.stream()
                .flatMap(ref -> {
                    var edges = new ArrayList<ArtifactGraph.Edge>();
                    if (ref.parentArtifactId() != null) {
                        edges.add(new ArtifactGraph.Edge(
                                ref.parentArtifactId(), ref.artifactId(), ArtifactGraph.EdgeType.DERIVED_FROM));
                    }
                    if (ref.supersedesArtifactId() != null) {
                        edges.add(new ArtifactGraph.Edge(
                                ref.supersedesArtifactId(), ref.artifactId(), ArtifactGraph.EdgeType.SUPERSEDES));
                    }
                    return edges.stream();
                })
                .toList();
        var semantic = new LinkedHashMap<String, Object>();
        semantic.put("rootArtifactId", product.rootArtifactId());
        semantic.put("sourceArtifacts", sourceArtifacts);
        semantic.put("relationships", relationships);
        semantic.put("requirementManifestId", manifestId);
        semantic.put("requirementItems", execution.requirementItems());
        // Bundle ID 是每次召回的审计引用，不参与相同上下文的语义 Hash。
        semantic.put("executionItems", execution.executionItems());
        semantic.put("gitBaseRevisions", gitBases);
        semantic.put("staleReferences", stale);
        semantic.put("feedbackNotes", feedbackNotes);
        var snapshotHash = artifacts.calculateHash(semantic);
        return new ArtifactContextSnapshot(
                "snapshot-" + snapshotHash.substring(0, 24),
                snapshotHash,
                product.rootArtifactId(),
                List.copyOf(sourceArtifacts),
                relationships,
                artifacts.effectiveHeads(product.rootArtifactId()),
                execution.systemId(),
                execution.requirementManifestId(),
                execution.requirementItems(),
                execution.executionBundleId(),
                execution.executionItems(),
                gitBases,
                Instant.now(),
                List.copyOf(stale),
                feedbackNotes,
                ArtifactRef.from(product),
                product.content(),
                planning == null ? null : ArtifactRef.from(planning),
                // Worker 将上下文内容按字典读取；没有下游产物时返回空对象，引用仍保持为空。
                planning == null ? objectMapper.createObjectNode() : planning.content(),
                previous == null ? null : ArtifactRef.from(previous),
                previous == null ? objectMapper.createObjectNode() : previous.content(),
                previous == null ? List.of() : artifacts.transitions(previous.artifactId()).stream()
                        .map(value -> new TransitionContext(
                                value.transitionId(), value.fromStatus(), value.toStatus(), value.actor(),
                                value.note(), value.createdAt()))
                        .toList());
    }

    private List<String> feedbackNotes(Artifact product, Artifact planning, Artifact previous) {
        var notes = new LinkedHashSet<String>();
        if (previous != null) {
            artifacts.transitions(previous.artifactId()).stream()
                    .filter(value -> value.toStatus() == ArtifactStatus.REJECTED
                            || value.toStatus() == ArtifactStatus.SUPERSEDED)
                    .map(ArtifactTransition::note)
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(value -> addFeedback(notes, value));
        }
        for (var artifact : new Artifact[]{product, planning, previous}) {
            if (artifact == null) continue;
            artifacts.evidence(artifact.artifactId()).stream()
                    .filter(value -> Set.of(
                            ArtifactEvidenceType.WorkerBlocked.name(),
                            ArtifactEvidenceType.ReworkStarted.name(),
                            ArtifactEvidenceType.RevisionRequested.name()).contains(value.evidenceType()))
                    .map(this::feedbackNote)
                    .filter(value -> !value.isBlank())
                    .forEach(value -> addFeedback(notes, value));
        }
        return List.copyOf(notes);
    }

    private String feedbackNote(ArtifactEvidence evidence) {
        return evidence.payload().path("note").asText("").trim();
    }

    private void addFeedback(Set<String> notes, String value) {
        if (notes.stream().noneMatch(existing -> existing.contains(value) || value.contains(existing))) {
            notes.add(value);
        }
    }

    private Artifact previous(Request request, Artifact product, Artifact planning) {
        if (request.previousArtifact() == null) return null;
        var previous = artifacts.requireExact(request.previousArtifact());
        requireScope(request, previous);
        var expectedType = "planning".equals(request.phase()) ? ArtifactType.PLANNING : ArtifactType.CODING;
        var expectedParent = planning == null ? product.artifactId() : planning.artifactId();
        var validParent = "planning".equals(request.phase())
                ? previous.rootArtifactId().equals(product.rootArtifactId())
                : Objects.equals(previous.parentArtifactId(), expectedParent);
        if (previous.artifactType() != expectedType || !validParent) {
            throw new IllegalStateException("上一 Artifact 不属于当前精确父链");
        }
        return previous;
    }

    private void requireScope(Request request, Artifact artifact) {
        if (!artifact.systemId().equals(request.systemId())
                || !artifact.prdId().equals(request.prdId())
                || !artifact.workItemId().equals(request.workItemId())) {
            throw new IllegalStateException("Artifact 不属于当前 Context 请求");
        }
    }

    public record Request(
            String systemId,
            String prdId,
            String workItemId,
            String requirementManifestId,
            String phase,
            ArtifactRef productArtifact,
            ArtifactRef planningArtifact,
            ArtifactRef previousArtifact,
            Map<String, String> gitBaseRevisions) {
    }

    public record ArtifactContextSnapshot(
            String snapshotId,
            String snapshotHash,
            String rootArtifactId,
            List<ArtifactRef> sourceArtifacts,
            List<ArtifactGraph.Edge> relationships,
            Map<ArtifactType, ArtifactRef> effectiveHeads,
            String systemId,
            String requirementManifestId,
            List<ContextItem> requirementItems,
            String executionBundleId,
            List<ContextItem> executionItems,
            Map<String, String> gitBaseRevisions,
            Instant builtAt,
            List<String> staleReferences,
            List<String> feedbackNotes,
            ArtifactRef productArtifact,
            JsonNode productContent,
            ArtifactRef planningArtifact,
            JsonNode planningContent,
            ArtifactRef previousArtifact,
            JsonNode previousContent,
            List<TransitionContext> previousTransitions) {
    }

    public record TransitionContext(
            String transitionId,
            ArtifactStatus fromStatus,
            ArtifactStatus toStatus,
            String actor,
            String note,
            Instant createdAt) {
    }
}
