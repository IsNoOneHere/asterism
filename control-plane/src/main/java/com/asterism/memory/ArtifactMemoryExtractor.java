package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactEvidence;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactType;
import com.asterism.event.DomainEventRecord;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

@Component
public class ArtifactMemoryExtractor {
    private static final List<String> UNCONFIRMED_MARKERS = List.of(
            "备选", "替代方案", "待定", "open question", "todo", "临时", "未确认", "被否定");
    private static final List<String> DECISION_MARKERS = List.of(
            "采用", "使用", "选择", "复用", "基于", "通过", "改为", "不使用", "保持");
    private static final List<String> CONSTRAINT_MARKERS = List.of(
            "必须", "禁止", "不得", "不能", "只能", "约束", "不允许");

    private final ArtifactService artifacts;

    public ArtifactMemoryExtractor(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    public List<MemoryCandidateService.CandidateInput> extract(
            Artifact artifact,
            DomainEventRecord event,
            ArtifactEvidence evidence) {
        return switch (event.eventType()) {
            case "PRDConfirmed" -> product(artifact, event);
            case "CodingPlanApproved" -> planning(artifact, event);
            case "ModificationCompleted" -> coding(artifact, event);
            case "ValidationFailed" -> validationFailure(artifact, event, evidence);
            default -> List.of();
        };
    }

    private List<MemoryCandidateService.CandidateInput> product(
            Artifact artifact,
            DomainEventRecord event) {
        if (artifact.artifactType() != ArtifactType.PRODUCT) return List.of();
        var content = artifact.content();
        var title = clean(content.path("title").asText());
        var parts = new ArrayList<String>();
        add(parts, "业务目标", content.path("goal").asText());
        add(parts, "业务范围", content.path("scope").asText());
        var criteria = texts(content.path("acceptanceCriteria")).stream().limit(4).toList();
        if (!criteria.isEmpty()) parts.add("业务规则：" + String.join("；", criteria));
        if (parts.isEmpty()) return List.of();
        return List.of(input(
                artifact, event, MemoryType.FACT, MemoryCandidateService.ARTIFACT_APPROVED,
                limit("业务事实 · " + (title.isBlank() ? "Product v" + artifact.version() : title), 80),
                limit(String.join("\n", parts), 1000), 0.92,
                MemoryApplicability.PROJECT, targets(artifact), auditEvidence(content, event)));
    }

    private List<MemoryCandidateService.CandidateInput> planning(
            Artifact artifact,
            DomainEventRecord event) {
        if (artifact.artifactType() != ArtifactType.PLANNING) return List.of();
        var lines = planLines(artifact.content().path("planMarkdown").asText());
        var constraints = lines.stream().filter(line -> containsAny(line, CONSTRAINT_MARKERS)).limit(6).toList();
        var decisions = lines.stream()
                .filter(line -> !constraints.contains(line))
                .filter(line -> containsAny(line, DECISION_MARKERS))
                .limit(6)
                .toList();
        if (decisions.isEmpty()) {
            decisions = lines.stream().filter(line -> !constraints.contains(line)).limit(5).toList();
        }
        var result = new ArrayList<MemoryCandidateService.CandidateInput>();
        var evidence = evidence(artifact.content(), event);
        var targets = targets(artifact);
        if (!decisions.isEmpty()) {
            result.add(input(
                    artifact, event, MemoryType.DECISION, MemoryCandidateService.ARTIFACT_APPROVED,
                    "技术决策 · Planning v" + artifact.version(),
                    limit("已批准的技术路线：" + String.join("；", decisions), 1000),
                    0.84, MemoryApplicability.PROJECT, targets, evidence));
        }
        if (!constraints.isEmpty()) {
            result.add(input(
                    artifact, event, MemoryType.CONSTRAINT, MemoryCandidateService.ARTIFACT_APPROVED,
                    "技术约束 · Planning v" + artifact.version(),
                    limit("已批准的实现约束：" + String.join("；", constraints), 1000),
                    0.90, MemoryApplicability.PROJECT, targets, evidence));
        }
        return List.copyOf(result);
    }

    private List<MemoryCandidateService.CandidateInput> coding(
            Artifact artifact,
            DomainEventRecord event) {
        if (artifact.artifactType() != ArtifactType.CODING) return List.of();
        var content = artifact.content();
        var summaries = new LinkedHashSet<String>();
        var overall = clean(content.path("summary").asText());
        if (!overall.isBlank()) summaries.add(overall);
        var paths = new LinkedHashSet<String>();
        for (var change : content.path("repoChanges")) {
            var summary = clean(change.path("summary").asText());
            if (!summary.isBlank()) summaries.add(summary);
            texts(change.path("changedPaths")).stream().limit(8).forEach(paths::add);
        }
        if (summaries.isEmpty()) return List.of();
        var body = new ArrayList<String>();
        body.add("实现经验：" + String.join("；", summaries.stream().limit(5).toList()));
        if (!paths.isEmpty()) body.add("涉及范围：" + String.join("、", paths.stream().limit(12).toList()));
        return List.of(input(
                artifact, event, MemoryType.EXPERIENCE, MemoryCandidateService.CODING_COMPLETED,
                "代码经验 · Coding v" + artifact.version(),
                limit(String.join("\n", body), 1000), 0.72,
                MemoryApplicability.PROJECT, targets(artifact), evidence(content, event)));
    }

    private List<MemoryCandidateService.CandidateInput> validationFailure(
            Artifact artifact,
            DomainEventRecord event,
            ArtifactEvidence evidence) {
        if (artifact.artifactType() != ArtifactType.CODING
                && artifact.artifactType() != ArtifactType.VALIDATION) return List.of();
        var command = commandLabel(evidence == null ? null : evidence.payload());
        var title = artifact.artifactType() == ArtifactType.VALIDATION
                ? "问题经验 · Validation v" + artifact.version()
                : "问题经验 · Coding v" + artifact.version();
        var content = "验证问题：" + (command.isBlank() ? "代码验证未通过" : command + " 未通过")
                + "。[待补充] 在确认进入项目记忆前，补充根因、已验证的解决方式和避免事项。";
        var refs = new ArrayList<String>();
        refs.add(event.eventId());
        if (evidence != null) refs.add(evidence.evidenceId());
        return List.of(input(
                artifact, event, MemoryType.EXPERIENCE, MemoryCandidateService.VALIDATION_FAILED,
                title, content, 0.70, MemoryApplicability.PROJECT,
                targets(artifact), List.copyOf(refs)));
    }

    private MemoryCandidateService.CandidateInput input(
            Artifact artifact,
            DomainEventRecord event,
            MemoryType memoryType,
            String sourceKind,
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            List<String> targets,
            List<String> evidence) {
        return new MemoryCandidateService.CandidateInput(
                artifact.systemId(), artifact.systemId(), memoryType, artifact.artifactId(), sourceKind,
                title, content, confidence, applicability, null, targets, evidence,
                event.eventId(), "memory-extractor");
    }

    private List<String> targets(Artifact artifact) {
        var product = artifacts.findAncestors(artifact.artifactId()).stream()
                .filter(value -> value.artifactType() == ArtifactType.PRODUCT)
                .findFirst()
                .orElse(null);
        if (product == null) return List.of();
        var values = new LinkedHashSet<String>();
        for (var target : product.content().path("targets")) {
            var entryId = clean(target.path("entryId").asText());
            if (!entryId.isBlank()) values.add(entryId);
        }
        return List.copyOf(values);
    }

    private List<String> auditEvidence(JsonNode content, DomainEventRecord event) {
        var result = new LinkedHashSet<String>();
        result.add(event.eventId());
        texts(content.path("auditRefs")).forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> evidence(JsonNode content, DomainEventRecord event) {
        var result = new LinkedHashSet<String>();
        result.add(event.eventId());
        texts(content.path("evidenceRefs")).forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> planLines(String markdown) {
        var lines = new ArrayList<String>();
        var inCode = false;
        for (var raw : text(markdown).lines().toList()) {
            var trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode || trimmed.isBlank() || trimmed.startsWith("#")) continue;
            var value = clean(trimmed.replaceFirst("^[-*+\\d.()\\s]+", ""));
            var lower = value.toLowerCase(Locale.ROOT);
            if (value.length() < 4 || UNCONFIRMED_MARKERS.stream().anyMatch(lower::contains)) continue;
            lines.add(value);
        }
        return List.copyOf(new LinkedHashSet<>(lines));
    }

    private String commandLabel(JsonNode payload) {
        if (payload == null) return "";
        var command = clean(payload.path("failedCommand").asText());
        if (command.isBlank()) return "";
        var first = command.split("\\s+", 2)[0];
        var slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        return limit(slash >= 0 ? first.substring(slash + 1) : first, 80);
    }

    private List<String> texts(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        return StreamSupport.stream(value.spliterator(), false)
                .map(JsonNode::asText)
                .map(this::clean)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private void add(List<String> values, String label, String value) {
        var cleaned = clean(value);
        if (!cleaned.isBlank()) values.add(label + "：" + cleaned);
    }

    private boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    private String clean(String value) {
        return text(value).replaceAll("\\s+", " ").trim();
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
