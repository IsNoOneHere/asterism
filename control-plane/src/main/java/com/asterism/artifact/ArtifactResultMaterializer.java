package com.asterism.artifact;

import com.asterism.event.DomainEventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ArtifactResultMaterializer {
    public static final int RESULT_SCHEMA_VERSION = 1;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer|api[_-]?key|access[_-]?token|secret[_-]?token|password)\\s*[:=]?\\s*[^\\s,;]+"
    );

    private final ArtifactService artifacts;
    private final ObjectMapper objectMapper;

    public ArtifactResultMaterializer(ArtifactService artifacts, ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
    }

    /** 只有确定性 Workflow patch 写入的版本标记才启用新物化，旧历史事件保持原语义。 */
    public String transitionId(ArtifactTransitionService.EventMetadata metadata,
                               Map<String, Object> payload) {
        if (number(payload.get("artifactResultVersion")) != RESULT_SCHEMA_VERSION) return null;
        var supported = metadata.eventType() == DomainEventType.ValidationPassed
                || metadata.eventType() == DomainEventType.ValidationFailed
                || metadata.eventType() == DomainEventType.ReleaseCompleted
                || metadata.eventType() == DomainEventType.WorkerBlocked
                && "validation".equals(text(payload.get("failedPhase")).toLowerCase(Locale.ROOT));
        return supported ? metadata.idempotencyKey() + ":result-artifact" : null;
    }

    public Materialization materialize(ArtifactTransitionService.EventMetadata metadata,
                                       Map<String, Object> payload, String transitionId) {
        var plan = plan(metadata, payload, true);
        var previous = artifacts.headRef(plan.parent().rootArtifactId(), plan.type());
        var mutation = artifacts.createApprovedResult(
                plan.type(), metadata.metadata(), ArtifactRef.from(plan.parent()), previous, previous,
                plan.content(), transitionId, plan.note());
        return new Materialization(mutation, transitionId, plan.commandHash(), plan.note());
    }

    /** 幂等重放按稳定输入重算 Hash；父 Artifact 后续失效不会改变原命令。 */
    public String commandHash(ArtifactTransitionService.EventMetadata metadata,
                              Map<String, Object> payload) {
        return plan(metadata, payload, false).commandHash();
    }

    private Plan plan(ArtifactTransitionService.EventMetadata metadata,
                      Map<String, Object> payload, boolean enforceCurrentRoute) {
        return metadata.eventType() == DomainEventType.ReleaseCompleted
                ? releasePlan(metadata, payload, enforceCurrentRoute)
                : validationPlan(metadata, payload, enforceCurrentRoute);
    }

    private Plan validationPlan(ArtifactTransitionService.EventMetadata metadata,
                                Map<String, Object> payload, boolean enforceCurrentRoute) {
        var coding = requireScoped(
                text(payload.get("codingArtifactId")), ArtifactType.CODING, metadata);
        // Validation 只能绑定当前 Coding Head，失败结果也不能把 Coding Artifact 改成 REJECTED。
        if (enforceCurrentRoute) coding = artifacts.requireEffectiveApproved(approvedRef(coding));
        var mode = validationMode(payload);
        var result = validationResult(metadata.eventType(), payload);
        var content = new ValidationArtifactContent(
                value(payload, "validationRunId", metadata.caseId() + ":" + metadata.causationId()),
                mode,
                result,
                commands(payload),
                redact(first(payload, "errorSummary", "stderrTail", "detail", "reason"), 2000),
                redact(first(payload, "manualEvidence", "evidence", "note"), 4000),
                coding.contentHash(),
                completedAt(payload));
        var note = "验证结果 " + result.name();
        return new Plan(
                ArtifactType.VALIDATION, coding, content, note,
                commandHash(metadata, ArtifactType.VALIDATION, coding, content));
    }

    private Plan releasePlan(ArtifactTransitionService.EventMetadata metadata,
                             Map<String, Object> payload, boolean enforceCurrentRoute) {
        var validation = requireScoped(
                text(payload.get("validationArtifactId")), ArtifactType.VALIDATION, metadata);
        if (enforceCurrentRoute) validation = artifacts.requirePassedValidation(approvedRef(validation));
        var coding = requireScoped(validation.parentArtifactId(), ArtifactType.CODING, metadata);
        var content = new ReleaseArtifactContent(
                value(payload, "releaseId", metadata.caseId() + ":" + metadata.causationId()),
                value(payload, "releaseMode", inferReleaseMode(payload)),
                value(payload, "targetKey", "default"),
                releases(payload),
                approvedRef(coding),
                approvedRef(validation),
                completedAt(payload));
        return new Plan(
                ArtifactType.RELEASE, validation, content, "发布已完成",
                commandHash(metadata, ArtifactType.RELEASE, validation, content));
    }

    private String commandHash(ArtifactTransitionService.EventMetadata metadata, ArtifactType type,
                               Artifact parent, ArtifactContent content) {
        var command = new LinkedHashMap<String, Object>();
        command.put("kind", "Materialize" + type.name());
        command.put("systemId", metadata.systemId());
        command.put("prdId", metadata.prdId());
        command.put("workItemId", metadata.workItemId());
        command.put("caseId", metadata.caseId());
        command.put("parentArtifactId", parent.artifactId());
        command.put("parentContentHash", parent.contentHash());
        command.put("content", content);
        return artifacts.calculateHash(command);
    }

    private ArtifactRef approvedRef(Artifact artifact) {
        return new ArtifactRef(
                artifact.artifactId(), artifact.artifactType(), artifact.version(), artifact.contentHash(),
                artifact.rootArtifactId(), artifact.parentArtifactId(), artifact.supersedesArtifactId(),
                ArtifactStatus.APPROVED);
    }

    private Instant completedAt(Map<String, Object> payload) {
        var value = text(payload.get("completedAt"));
        if (value.isBlank()) throw new IllegalArgumentException("结果事件缺少确定性的 completedAt");
        try {
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("completedAt 必须是 UTC ISO-8601 时间", error);
        }
    }

    private Artifact requireScoped(String artifactId, ArtifactType type,
                                   ArtifactTransitionService.EventMetadata metadata) {
        var artifact = artifacts.require(artifactId);
        if (artifact.artifactType() != type
                || !artifact.systemId().equals(metadata.systemId())
                || !artifact.prdId().equals(metadata.prdId())
                || !artifact.workItemId().equals(metadata.workItemId())
                || !artifact.caseId().equals(metadata.caseId())) {
            throw new ArtifactConflictException(type + " Artifact 不属于当前事件工作项");
        }
        return artifact;
    }

    private ValidationArtifactContent.Mode validationMode(Map<String, Object> payload) {
        if (Boolean.TRUE.equals(payload.get("skipped"))) return ValidationArtifactContent.Mode.SKIP;
        try {
            return ValidationArtifactContent.Mode.valueOf(
                    value(payload, "validationMode", "AUTO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("validationMode 不是 AUTO/MANUAL/SKIP");
        }
    }

    private ValidationArtifactContent.Result validationResult(
            DomainEventType eventType, Map<String, Object> payload) {
        if (eventType == DomainEventType.WorkerBlocked) return ValidationArtifactContent.Result.ERROR;
        if (eventType == DomainEventType.ValidationFailed) return ValidationArtifactContent.Result.FAILED;
        return Boolean.TRUE.equals(payload.get("skipped"))
                ? ValidationArtifactContent.Result.SKIPPED
                : ValidationArtifactContent.Result.PASSED;
    }

    private List<ValidationArtifactContent.CommandResult> commands(Map<String, Object> payload) {
        var fallbackRepo = text(payload.get("repo"));
        var values = objectMapper.convertValue(
                payload.getOrDefault("commands", List.of()), new TypeReference<List<Map<String, Object>>>() {});
        return values.stream().map(value -> new ValidationArtifactContent.CommandResult(
                value(value, "repo", fallbackRepo),
                text(value.get("command")),
                value.get("exitCode") instanceof Number number ? number.intValue() : null)).toList();
    }

    private List<ReleaseArtifactContent.RepositoryRelease> releases(Map<String, Object> payload) {
        var rows = objectMapper.convertValue(
                payload.getOrDefault("repositories", List.of()), new TypeReference<List<Map<String, Object>>>() {});
        if (rows.isEmpty()) rows = List.of(new LinkedHashMap<>(payload));
        var result = new ArrayList<ReleaseArtifactContent.RepositoryRelease>();
        for (var row : rows) {
            var paths = objectMapper.convertValue(
                    row.getOrDefault("changedPaths", payload.getOrDefault("changedPaths", List.of())),
                    new TypeReference<List<String>>() {});
            result.add(new ReleaseArtifactContent.RepositoryRelease(
                    value(row, "repo", text(payload.get("repo"))),
                    value(row, "branch", text(payload.get("branch"))),
                    value(row, "commitHash", text(payload.get("commitHash"))),
                    row.get("mrIid") instanceof Number number ? number.intValue() : null,
                    text(row.get("mrUrl")),
                    value(row, "finalState", value(row, "state", "completed")),
                    paths));
        }
        return List.copyOf(result);
    }

    private String inferReleaseMode(Map<String, Object> payload) {
        var json = String.valueOf(payload);
        return json.contains("mrIid") || json.contains("mrUrl") ? "gitlab" : "local";
    }

    private String first(Map<String, Object> payload, String... keys) {
        for (var key : keys) {
            var value = text(payload.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String value(Map<String, Object> values, String key, String fallback) {
        var value = text(values.get(key));
        return value.isBlank() ? fallback : value;
    }

    private String redact(String value, int maxLength) {
        var redacted = SECRET.matcher(value == null ? "" : value).replaceAll("$1 [REDACTED]");
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    public record Materialization(
            ArtifactService.Mutation mutation,
            String transitionId,
            String commandHash,
            String note) {
    }

    private record Plan(
            ArtifactType type,
            Artifact parent,
            ArtifactContent content,
            String note,
            String commandHash) {
    }
}
