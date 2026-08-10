package com.asterism.artifact;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ArtifactTransitionService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactTransitionService.class);
    private static final Map<String, Set<DomainEventType>> EVENT_POLICY = Map.of(
            "ProposePlanningArtifact", Set.of(DomainEventType.CodingPlanProposed),
            "ApprovePlanningArtifact", Set.of(DomainEventType.CodingPlanApproved),
            "RejectPlanningArtifact", Set.of(DomainEventType.CodingPlanRejected),
            "ProposeCodingArtifact", Set.of(DomainEventType.ModificationCompleted, DomainEventType.WorkerBlocked),
            "ApproveCodingArtifact", Set.of(DomainEventType.PatchApplied, DomainEventType.ValidationPassed),
            "RejectCodingArtifact", Set.of(DomainEventType.PatchRejected, DomainEventType.ValidationFailed),
            "SupersedeArtifact", Set.of(DomainEventType.CodingPlanInvalidated, DomainEventType.RevisionRequested));
    private static final Set<DomainEventType> TRANSITION_REQUIRED = Set.of(
            DomainEventType.CodingPlanProposed,
            DomainEventType.CodingPlanApproved,
            DomainEventType.CodingPlanRejected,
            DomainEventType.CodingPlanInvalidated,
            DomainEventType.ModificationCompleted,
            DomainEventType.PatchRejected);
    private static final Set<DomainEventType> EVIDENCE_REQUIRED = Set.of(
            DomainEventType.CodingPlanProposed,
            DomainEventType.ModificationCompleted,
            DomainEventType.WorkerBlocked,
            DomainEventType.ReworkStarted,
            DomainEventType.RevisionRequested,
            DomainEventType.PatchApplied,
            DomainEventType.PatchApplyBlocked,
            DomainEventType.PatchRejected,
            DomainEventType.ValidationPassed,
            DomainEventType.ValidationFailed,
            DomainEventType.RepositoryReleasePrepared,
            DomainEventType.MergeRequestCreated,
            DomainEventType.MergeRequestMerged,
            DomainEventType.MergeRequestClosed,
            DomainEventType.ReleaseCompleted);
    private static final Map<DomainEventType, Set<ArtifactEvidenceType>> EVIDENCE_POLICY = Map.ofEntries(
            Map.entry(DomainEventType.CodingPlanProposed, Set.of(ArtifactEvidenceType.PlanningExecution)),
            Map.entry(DomainEventType.ModificationCompleted, Set.of(ArtifactEvidenceType.CodingExecution)),
            Map.entry(DomainEventType.WorkerBlocked, Set.of(ArtifactEvidenceType.WorkerBlocked)),
            Map.entry(DomainEventType.ReworkStarted, Set.of(ArtifactEvidenceType.ReworkStarted)),
            Map.entry(DomainEventType.RevisionRequested, Set.of(ArtifactEvidenceType.RevisionRequested)),
            Map.entry(DomainEventType.PatchApplied, Set.of(ArtifactEvidenceType.PatchApplied)),
            Map.entry(DomainEventType.PatchApplyBlocked, Set.of(ArtifactEvidenceType.PatchApplyBlocked)),
            Map.entry(DomainEventType.PatchRejected, Set.of(ArtifactEvidenceType.PatchRejected)),
            Map.entry(DomainEventType.ValidationPassed, Set.of(ArtifactEvidenceType.ValidationPassed)),
            Map.entry(DomainEventType.ValidationFailed, Set.of(ArtifactEvidenceType.ValidationFailed)),
            Map.entry(DomainEventType.RepositoryReleasePrepared, Set.of(
                    ArtifactEvidenceType.RepositoryReleasePrepared, ArtifactEvidenceType.Commit)),
            Map.entry(DomainEventType.MergeRequestCreated, Set.of(
                    ArtifactEvidenceType.MergeRequestCreated, ArtifactEvidenceType.MergeRequest)),
            Map.entry(DomainEventType.MergeRequestMerged, Set.of(
                    ArtifactEvidenceType.MergeRequestMerged, ArtifactEvidenceType.MergeRequest)),
            Map.entry(DomainEventType.MergeRequestClosed, Set.of(
                    ArtifactEvidenceType.MergeRequestClosed, ArtifactEvidenceType.MergeRequest)),
            Map.entry(DomainEventType.ReleaseCompleted, Set.of(
                    ArtifactEvidenceType.ReleaseCompleted, ArtifactEvidenceType.Release)));

    private final ArtifactService artifacts;
    private final ArtifactRepository repository;
    private final DomainEventService events;
    private final ArtifactResultMaterializer resultArtifacts;
    private final ObjectMapper objectMapper;
    private final ObjectMapper contentMapper;

    @Autowired
    public ArtifactTransitionService(ArtifactService artifacts, ArtifactRepository repository,
                                     DomainEventService events, ArtifactResultMaterializer resultArtifacts,
                                     ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.repository = repository;
        this.events = events;
        this.resultArtifacts = resultArtifacts;
        this.objectMapper = objectMapper;
        // Artifact Content 是强类型契约，旧字段不能被 Jackson 静默丢弃。
        this.contentMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    ArtifactTransitionService(ArtifactService artifacts, ArtifactRepository repository,
                              DomainEventService events, ObjectMapper objectMapper) {
        this(artifacts, repository, events,
                new ArtifactResultMaterializer(artifacts, objectMapper), objectMapper);
    }

    @Transactional
    public Result ingest(EventMetadata metadata, Map<String, Object> rawPayload,
                         ArtifactTransitionRequest transitionRequest,
                         ArtifactEvidenceRequest evidenceRequest) {
        if (TRANSITION_REQUIRED.contains(metadata.eventType()) && transitionRequest == null) {
            throw new IllegalArgumentException(metadata.eventType() + " 必须由 Artifact Transition 主动作提交");
        }
        if (EVIDENCE_REQUIRED.contains(metadata.eventType()) && evidenceRequest == null) {
            throw new IllegalArgumentException(metadata.eventType() + " 必须提交 Artifact Evidence");
        }
        if (evidenceRequest != null) {
            validateEvidenceEvent(evidenceRequest.evidenceType(), metadata.eventType());
        }
        if (transitionRequest != null) {
            validateEvent(transitionRequest.kind(), metadata.eventType());
            repository.lockCommand(commandLock("transition", transitionRequest.transitionId()));
        }
        if (evidenceRequest != null) {
            repository.lockCommand(commandLock("evidence", evidenceRequest.evidenceId()));
        }
        if (transitionRequest != null && evidenceRequest != null
                && !Objects.equals(transitionRequest.transitionId(), evidenceRequest.transitionId())) {
            throw new ArtifactConflictException("Transition 与 Evidence 的 transitionId 不一致");
        }
        if (transitionRequest == null && evidenceRequest == null) {
            return new Result(appendEvent(metadata, rawPayload), null, null, null);
        }
        ArtifactService.Mutation mutation = null;
        ArtifactTransition transition = null;
        var payload = new LinkedHashMap<>(rawPayload == null ? Map.of() : rawPayload);
        var resultTransitionId = transitionRequest == null
                ? resultArtifacts.transitionId(metadata, payload) : null;
        if (resultTransitionId != null) {
            repository.lockCommand(commandLock("transition", resultTransitionId));
            var existing = repository.findTransition(resultTransitionId).orElse(null);
            if (existing != null) {
                var commandHash = resultArtifacts.commandHash(metadata, payload);
                if (!existing.commandHash().equals(commandHash)) {
                    throw new ArtifactConflictException(
                            "结果 Artifact 幂等键已被不同 payload 或精确父版本使用");
                }
                var artifact = artifacts.require(existing.artifactId());
                var reference = refAtTransition(artifact, existing);
                addRef(payload, reference, resultTransitionId);
                var event = appendEvent(metadata, payload);
                var normalizedEvidence = evidenceForResult(evidenceRequest, reference, resultTransitionId);
                var evidence = appendEvidenceIfRequested(
                        metadata, normalizedEvidence, event, existing.commandHash());
                return new Result(event, reference, existing, evidence);
            }
        }
        var commandHash = "";
        if (transitionRequest != null) {
            commandHash = artifacts.calculateHash(transitionRequest);
            var existing = repository.findTransition(transitionRequest.transitionId()).orElse(null);
            if (existing != null) {
                if (!existing.commandHash().equals(commandHash)) {
                    throw new ArtifactConflictException(
                            "transitionId 已被不同 parent、expectedHead、ArtifactRef 或 Content 使用");
                }
                var artifact = artifacts.require(existing.artifactId());
                var reference = refAtTransition(artifact, existing);
                addRef(payload, reference, transitionRequest.transitionId());
                var event = appendEvent(metadata, payload);
                var normalizedEvidence = evidenceForResult(evidenceRequest, reference);
                var evidence = appendEvidenceIfRequested(metadata, normalizedEvidence, event, commandHash);
                return new Result(event, reference, existing, evidence);
            }
            var command = command(transitionRequest, metadata.actorId());
            mutation = apply(command, metadata.metadata());
            addRef(payload, ArtifactRef.from(mutation.artifact()), transitionRequest.transitionId());
        } else if (resultTransitionId != null) {
            var materialized = resultArtifacts.materialize(metadata, payload, resultTransitionId);
            mutation = materialized.mutation();
            commandHash = materialized.commandHash();
            addRef(payload, ArtifactRef.from(mutation.artifact()), resultTransitionId);
        }
        var event = appendEvent(metadata, payload);
        if (mutation != null) {
            var appliedTransitionId = transitionRequest == null
                    ? resultTransitionId : transitionRequest.transitionId();
            transition = insertTransition(
                    appliedTransitionId, mutation.artifact(),
                    transitionRequest == null ? null : transitionFrom(transitionRequest),
                    mutation.artifact().status(), metadata.actorId(),
                    transitionRequest == null ? mutation.artifact().reviewNote() : transitionRequest.note(),
                    event.eventId(), commandHash, Instant.now());
            if (transitionRequest != null && transitionRequest.kind().startsWith("Propose")
                    && transitionRequest.supersedes() != null
                    && transitionRequest.supersedes().status() == ArtifactStatus.PROPOSED) {
                insertTransition(
                        transitionRequest.transitionId() + ":supersede:"
                                + transitionRequest.supersedes().artifactId(),
                        artifacts.require(transitionRequest.supersedes().artifactId()),
                        ArtifactStatus.PROPOSED, ArtifactStatus.SUPERSEDED, metadata.actorId(),
                        "已由 " + mutation.artifact().artifactId() + " 替代",
                        event.eventId(), commandHash, Instant.now());
            }
            if (mutation.previousHead() != null
                    && mutation.previousHead().status() == ArtifactStatus.APPROVED
                    && mutation.artifact().status() == ArtifactStatus.APPROVED
                    && !mutation.previousHead().artifactId().equals(mutation.artifact().artifactId())) {
                insertTransition(
                        appliedTransitionId + ":supersede:" + mutation.previousHead().artifactId(),
                        artifacts.require(mutation.previousHead().artifactId()),
                        ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED, metadata.actorId(),
                        "已由 " + mutation.artifact().artifactId() + " 替代",
                        event.eventId(), commandHash, Instant.now());
            }
            for (var change : mutation.downstreamChanges()) {
                insertTransition(
                        appliedTransitionId + ":invalidate:" + change.artifact().artifactId(),
                        change.artifact(), change.fromStatus(), change.toStatus(), metadata.actorId(),
                        change.note(), event.eventId(), commandHash, Instant.now());
            }
        }
        var normalizedEvidence = mutation == null
                ? evidenceRequest : evidenceForResult(
                        evidenceRequest, ArtifactRef.from(mutation.artifact()), resultTransitionId);
        var evidence = appendEvidenceIfRequested(metadata, normalizedEvidence, event, commandHash);
        log.info("Artifact Transition 已提交 transition={} event={} artifact={}",
                transitionRequest == null ? null : transitionRequest.transitionId(),
                event.eventId(), mutation == null ? null : mutation.artifact().artifactId());
        return new Result(event, mutation == null ? null : ArtifactRef.from(mutation.artifact()), transition, evidence);
    }

    @Transactional
    public Result confirmProduct(EventMetadata metadata, Map<String, Object> rawPayload,
                                 ArtifactService.Metadata scope, ProductArtifactContent content,
                                 ArtifactRef supersedes, ArtifactRef expectedHead,
                                 String transitionId) {
        repository.lockCommand(commandLock("transition", transitionId));
        var commandHash = productCommandHash(
                scope, content, supersedes, expectedHead, transitionId);
        var existing = repository.findTransition(transitionId).orElse(null);
        if (existing != null) {
            if (!existing.commandHash().equals(commandHash)) {
                throw new ArtifactConflictException("Product Transition 幂等参数冲突");
            }
            var artifact = artifacts.require(existing.artifactId());
            var reference = refAtTransition(artifact, existing);
            var payload = new LinkedHashMap<>(rawPayload);
            addRef(payload, reference, transitionId);
            return new Result(appendEvent(metadata, payload), reference, existing, null);
        }
        var mutation = artifacts.createApprovedProduct(
                scope, content, supersedes, expectedHead, transitionId);
        var invalidatedHeads = invalidateDownstreamHeads(
                mutation.artifact().rootArtifactId(), metadata.actorId(), mutation.artifact().artifactId());
        var payload = new LinkedHashMap<>(rawPayload);
        addRef(payload, ArtifactRef.from(mutation.artifact()), transitionId);
        var event = appendEvent(metadata, payload);
        var transition = insertTransition(
                transitionId, mutation.artifact(), null, ArtifactStatus.APPROVED,
                metadata.actorId(), "PRD 已确认", event.eventId(), commandHash, Instant.now());
        if (mutation.previousHead() != null) {
            insertTransition(
                    transitionId + ":supersede:" + mutation.previousHead().artifactId(),
                    artifacts.require(mutation.previousHead().artifactId()),
                    ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED, metadata.actorId(),
                    "已由 " + mutation.artifact().artifactId() + " 替代",
                    event.eventId(), commandHash, Instant.now());
        }
        for (var invalidated : invalidatedHeads) {
            insertTransition(
                    transitionId + ":invalidate:" + invalidated.artifactId(),
                    invalidated, ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED,
                    metadata.actorId(), "上游 ProductArtifact 已更新",
                    event.eventId(), commandHash, Instant.now());
        }
        return new Result(event, ArtifactRef.from(mutation.artifact()), transition, null);
    }

    @Transactional
    public VersionSelectionResult selectVersion(EventMetadata metadata, ArtifactRef target,
                                                Map<ArtifactType, ArtifactRef> expectedHeads,
                                                String transitionId) {
        repository.lockCommand(commandLock("transition", transitionId));
        var expected = expectedHeads == null ? Map.<ArtifactType, ArtifactRef>of() : Map.copyOf(expectedHeads);
        var command = new LinkedHashMap<String, Object>();
        command.put("kind", "SelectArtifactVersion");
        command.put("transitionId", transitionId);
        command.put("target", target);
        command.put("expectedHeads", expected);
        var commandHash = artifacts.calculateHash(command);
        var existing = repository.findTransition(transitionId).orElse(null);
        if (existing != null) {
            if (!existing.commandHash().equals(commandHash)) {
                throw new ArtifactConflictException("Artifact 版本切换幂等参数冲突");
            }
            var artifact = artifacts.require(existing.artifactId());
            return new VersionSelectionResult(
                    null, refAtTransition(artifact, existing),
                    artifacts.effectiveHeads(artifact.rootArtifactId()), List.of(existing));
        }

        var selected = artifacts.requireExact(target);
        if (!selected.systemId().equals(metadata.systemId())
                || !selected.prdId().equals(metadata.prdId())
                || !selected.workItemId().equals(metadata.workItemId())
                || !selected.caseId().equals(metadata.caseId())) {
            throw new ArtifactConflictException("Artifact 不属于当前工作项");
        }
        var activation = artifacts.activateVersion(target, expected, metadata.actorId());
        var routeVersions = new LinkedHashMap<String, Integer>();
        activation.effectiveHeads().forEach((type, ref) -> routeVersions.put(type.name(), ref.version()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("selectedType", activation.selectedArtifact().artifactType().name());
        payload.put("selectedVersion", activation.selectedArtifact().version());
        payload.put("currentRoute", routeVersions);
        var event = appendEvent(metadata, payload);

        var transitions = new ArrayList<ArtifactTransition>();
        var selectedChange = activation.statusChanges().stream()
                .filter(change -> change.artifact().artifactId().equals(activation.selectedArtifact().artifactId()))
                .findFirst().orElse(null);
        transitions.add(insertTransition(
                transitionId,
                activation.selectedArtifact(),
                selectedChange == null ? activation.selectedArtifact().status() : selectedChange.fromStatus(),
                ArtifactStatus.APPROVED,
                metadata.actorId(),
                selectedChange == null ? "确认继续使用当前版本" : selectedChange.note(),
                event.eventId(), commandHash, Instant.now()));
        for (var change : activation.statusChanges()) {
            if (change.artifact().artifactId().equals(activation.selectedArtifact().artifactId())) continue;
            transitions.add(insertTransition(
                    transitionId + ":route:" + change.artifact().artifactId(),
                    change.artifact(), change.fromStatus(), change.toStatus(),
                    metadata.actorId(), change.note(), event.eventId(), commandHash, Instant.now()));
        }
        log.info("Artifact 版本切换已审计 workItem={} type={} version={} transition={}",
                metadata.workItemId(), activation.selectedArtifact().artifactType(),
                activation.selectedArtifact().version(), transitionId);
        return new VersionSelectionResult(
                event, ArtifactRef.from(activation.selectedArtifact()),
                activation.effectiveHeads(), List.copyOf(transitions));
    }

    @Transactional
    public Result refreshProductManifest(EventMetadata metadata, ArtifactRef currentProduct,
                                         String requirementManifestId, String transitionId) {
        repository.lockCommand(commandLock("transition", transitionId));
        var existing = repository.findTransition(transitionId).orElse(null);
        if (existing != null) {
            var artifact = artifacts.require(existing.artifactId());
            var content = contentMapper.convertValue(artifact.content(), ProductArtifactContent.class);
            var scope = new ArtifactService.Metadata(
                    metadata.systemId(), metadata.prdId(), metadata.workItemId(),
                    metadata.caseId(), metadata.actorId());
            if (artifact.artifactType() != ArtifactType.PRODUCT
                    || !artifact.systemId().equals(metadata.systemId())
                    || !artifact.prdId().equals(metadata.prdId())
                    || !artifact.workItemId().equals(metadata.workItemId())
                    || !artifact.caseId().equals(metadata.caseId())
                    || !requirementManifestId.equals(
                    artifact.content().path("requirementManifestId").asText())
                    || !existing.commandHash().equals(
                    productCommandHash(
                            scope, content, currentProduct, currentProduct, transitionId))) {
                throw new ArtifactConflictException("Product Context Refresh 幂等参数冲突");
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("requirementManifestId", requirementManifestId);
            var reference = refAtTransition(artifact, existing);
            addRef(payload, reference, transitionId);
            return new Result(appendEvent(metadata, payload), reference, existing, null);
        }
        var current = artifacts.requireEffectiveApproved(currentProduct);
        if (!current.systemId().equals(metadata.systemId())
                || !current.prdId().equals(metadata.prdId())
                || !current.workItemId().equals(metadata.workItemId())
                || !current.caseId().equals(metadata.caseId())) {
            throw new ArtifactConflictException("ProductArtifact 不属于当前工作项");
        }
        var content = contentMapper.convertValue(current.content(), ProductArtifactContent.class);
        var auditRefs = new ArrayList<>(content.auditRefs());
        auditRefs.add("RequirementContextRefreshed:" + requirementManifestId);
        return confirmProduct(
                metadata,
                Map.of("requirementManifestId", requirementManifestId),
                new ArtifactService.Metadata(
                        current.systemId(), current.prdId(), current.workItemId(),
                        current.caseId(), metadata.actorId()),
                new ProductArtifactContent(
                        content.title(), content.goal(), content.scope(), content.acceptanceCriteria(),
                        content.targets(), content.citations(), requirementManifestId, auditRefs),
                ArtifactRef.from(current),
                ArtifactRef.from(current),
                transitionId);
    }

    private ArtifactTransitionCommand command(ArtifactTransitionRequest request, String fallbackActor) {
        var actor = fallbackActor == null || fallbackActor.isBlank() ? "worker" : fallbackActor;
        return switch (request.kind()) {
            case "ProposePlanningArtifact" -> new ArtifactTransitionCommand.ProposePlanningArtifact(
                    request.transitionId(), actor, request.parent(), request.supersedes(),
                    request.expectedHead(), contentMapper.convertValue(
                    request.content(), PlanningArtifactContent.class));
            case "ApprovePlanningArtifact" -> new ArtifactTransitionCommand.ApprovePlanningArtifact(
                    request.transitionId(), actor, request.artifact(), request.expectedHead(), request.note());
            case "RejectPlanningArtifact" -> new ArtifactTransitionCommand.RejectPlanningArtifact(
                    request.transitionId(), actor, request.artifact(), request.expectedHead(), request.note());
            case "ProposeCodingArtifact" -> new ArtifactTransitionCommand.ProposeCodingArtifact(
                    request.transitionId(), actor, request.parent(), request.supersedes(),
                    request.expectedHead(), contentMapper.convertValue(
                    request.content(), CodingArtifactContent.class));
            case "ApproveCodingArtifact" -> new ArtifactTransitionCommand.ApproveCodingArtifact(
                    request.transitionId(), actor, request.artifact(), request.expectedHead(), request.note());
            case "RejectCodingArtifact" -> new ArtifactTransitionCommand.RejectCodingArtifact(
                    request.transitionId(), actor, request.artifact(), request.expectedHead(), request.note());
            case "SupersedeArtifact" -> new ArtifactTransitionCommand.SupersedeArtifact(
                    request.transitionId(), actor, request.artifact(), request.expectedHead(), request.note());
            default -> throw new IllegalArgumentException("不支持的 Artifact Transition: " + request.kind());
        };
    }

    private String productCommandHash(ArtifactService.Metadata scope, ProductArtifactContent content,
                                      ArtifactRef supersedes, ArtifactRef expectedHead,
                                      String transitionId) {
        return artifacts.calculateHash(Map.of(
                "kind", "ConfirmProductArtifact",
                "transitionId", transitionId,
                "scope", scope,
                "content", content,
                "supersedes", supersedes == null ? "" : supersedes,
                "expectedHead", expectedHead == null ? "" : expectedHead));
    }

    private ArtifactService.Mutation apply(ArtifactTransitionCommand command, ArtifactService.Metadata metadata) {
        return switch (command) {
            case ArtifactTransitionCommand.ProposePlanningArtifact value ->
                    artifacts.createProposal(
                            ArtifactType.PLANNING, metadata, value.parent(), value.supersedes(),
                            value.expectedHead(), value.content(), value.transitionId());
            case ArtifactTransitionCommand.ApprovePlanningArtifact value ->
                    artifacts.approve(value.artifact(), value.expectedHead(), value.actor(), value.note());
            case ArtifactTransitionCommand.RejectPlanningArtifact value ->
                    artifacts.reject(value.artifact(), value.expectedHead(), value.actor(), value.note());
            case ArtifactTransitionCommand.ProposeCodingArtifact value ->
                    artifacts.createProposal(
                            ArtifactType.CODING, metadata, value.parent(), value.supersedes(),
                            value.expectedHead(), value.content(), value.transitionId());
            case ArtifactTransitionCommand.ApproveCodingArtifact value ->
                    artifacts.approve(value.artifact(), value.expectedHead(), value.actor(), value.note());
            case ArtifactTransitionCommand.RejectCodingArtifact value ->
                    artifacts.reject(value.artifact(), value.expectedHead(), value.actor(), value.note());
            case ArtifactTransitionCommand.SupersedeArtifact value ->
                    artifacts.supersede(value.artifact(), value.expectedHead(), value.actor(), value.note());
            case ArtifactTransitionCommand.AppendArtifactEvidence ignored ->
                    throw new IllegalArgumentException("Evidence Command 不能修改 Artifact 状态");
        };
    }

    private ArtifactStatus transitionFrom(ArtifactTransitionRequest request) {
        return request.artifact() == null ? null : request.artifact().status();
    }

    private ArtifactTransition insertTransition(String transitionId, Artifact artifact,
                                                ArtifactStatus from, ArtifactStatus to,
                                                String actor, String note, String eventId,
                                                String commandHash, Instant now) {
        var transition = new ArtifactTransition(
                transitionId, artifact.artifactId(), from, to, actor, note, eventId, commandHash, now);
        repository.insertTransition(transition);
        return transition;
    }

    private ArtifactEvidence appendEvidenceIfRequested(EventMetadata metadata,
                                                       ArtifactEvidenceRequest request,
                                                       DomainEventRecord event,
                                                       String transitionCommandHash) {
        if (request == null) return null;
        var command = evidenceCommand(request, metadata.actorId());
        var commandHash = artifacts.calculateHash(command);
        var existing = repository.findEvidence(command.evidenceId()).orElse(null);
        if (existing != null) {
            if (!existing.commandHash().equals(commandHash)) {
                throw new ArtifactConflictException("evidenceId 已被不同 ArtifactRef 或 Evidence 使用");
            }
            return existing;
        }
        var artifact = artifacts.requireExact(command.artifact());
        if (command.transitionId() != null) {
            var linked = repository.findTransition(command.transitionId())
                    .orElseThrow(() -> new ArtifactConflictException("Evidence 引用的 Transition 不存在"));
            if (!linked.artifactId().equals(artifact.artifactId())) {
                throw new ArtifactConflictException("Evidence 与 Transition 指向了不同 Artifact");
            }
        }
        var evidence = new ArtifactEvidence(
                command.evidenceId(), artifact.artifactId(), command.evidenceType().name(),
                command.payload() == null ? objectMapper.createObjectNode() : command.payload(),
                command.transitionId(), event.eventId(), command.actor(),
                commandHash.isBlank() ? transitionCommandHash : commandHash, Instant.now());
        repository.insertEvidence(evidence);
        return evidence;
    }

    private ArtifactTransitionCommand.AppendArtifactEvidence evidenceCommand(
            ArtifactEvidenceRequest request, String fallbackActor) {
        var actor = fallbackActor == null || fallbackActor.isBlank() ? "worker" : fallbackActor;
        return new ArtifactTransitionCommand.AppendArtifactEvidence(
                request.transitionId(), actor, request.evidenceId(), request.artifact(),
                request.evidenceType(), request.payload());
    }

    private ArtifactEvidenceRequest evidenceForResult(
            ArtifactEvidenceRequest request, ArtifactRef reference) {
        return evidenceForResult(request, reference, null);
    }

    private ArtifactEvidenceRequest evidenceForResult(
            ArtifactEvidenceRequest request, ArtifactRef reference, String fallbackTransitionId) {
        if (request == null) return null;
        if (request.artifact() != null
                && !request.artifact().artifactId().equals(reference.artifactId())) {
            throw new ArtifactConflictException("Transition 与 Evidence 指向了不同 Artifact");
        }
        return new ArtifactEvidenceRequest(
                request.evidenceId(), reference, request.evidenceType(),
                request.transitionId() == null ? fallbackTransitionId : request.transitionId(), request.payload());
    }

    private ArtifactRef refAtTransition(Artifact artifact, ArtifactTransition transition) {
        // 幂等重放返回原 Transition 状态，不能被 Artifact 后续状态推进污染。
        return new ArtifactRef(
                artifact.artifactId(), artifact.artifactType(), artifact.version(),
                artifact.contentHash(), artifact.rootArtifactId(), artifact.parentArtifactId(),
                artifact.supersedesArtifactId(), transition.toStatus());
    }

    private java.util.List<Artifact> invalidateDownstreamHeads(
            String rootArtifactId, String actorId, String productArtifactId) {
        var invalidated = new ArrayList<Artifact>();
        for (var type : java.util.List.of(
                ArtifactType.PLANNING, ArtifactType.CODING,
                ArtifactType.VALIDATION, ArtifactType.RELEASE)) {
            var head = artifacts.headRef(rootArtifactId, type);
            if (head == null) continue;
            invalidated.add(artifacts.supersede(
                    head, head, actorId,
                    "上游 ProductArtifact 已由 " + productArtifactId + " 替代").artifact());
        }
        return java.util.List.copyOf(invalidated);
    }

    private DomainEventRecord appendEvent(EventMetadata metadata, Map<String, Object> payload) {
        return events.append(new DomainEventService.AppendEvent(
                metadata.eventType(), metadata.systemId(), metadata.caseId(), metadata.prdId(),
                metadata.workItemId(), metadata.actorId(), metadata.source(), payload,
                metadata.correlationId(), metadata.causationId(), metadata.idempotencyKey()));
    }

    private void validateEvent(String kind, DomainEventType eventType) {
        var allowed = EVENT_POLICY.get(kind);
        if (allowed == null || !allowed.contains(eventType)) {
            throw new IllegalArgumentException(kind + " 不能由事件 " + eventType + " 触发");
        }
    }

    private void validateEvidenceEvent(ArtifactEvidenceType evidenceType, DomainEventType eventType) {
        var allowed = EVIDENCE_POLICY.get(eventType);
        if (evidenceType == null || allowed == null || !allowed.contains(evidenceType)) {
            throw new IllegalArgumentException(evidenceType + " Evidence 不能由事件 " + eventType + " 触发");
        }
    }

    private String commandLock(String kind, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(kind + " ID 不能为空");
        }
        return kind + ":" + id;
    }

    private void addRef(Map<String, Object> payload, ArtifactRef reference, String transitionId) {
        payload.put("artifactRef", reference);
        payload.put("transitionId", transitionId);
    }

    public record EventMetadata(
            DomainEventType eventType,
            String systemId,
            String caseId,
            String prdId,
            String workItemId,
            String actorId,
            String source,
            String correlationId,
            String causationId,
            String idempotencyKey) {

        ArtifactService.Metadata metadata() {
            return new ArtifactService.Metadata(systemId, prdId, workItemId, caseId, actorId);
        }
    }

    public record Result(
            DomainEventRecord event,
            ArtifactRef artifactRef,
            ArtifactTransition transition,
            ArtifactEvidence evidence) {
    }

    public record VersionSelectionResult(
            DomainEventRecord event,
            ArtifactRef selectedArtifact,
            Map<ArtifactType, ArtifactRef> effectiveHeads,
            List<ArtifactTransition> transitions) {
    }
}
