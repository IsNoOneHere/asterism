package com.asterism.memory;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.prd.PrdDraftCodec;
import com.asterism.prd.PrdSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemMemoryLearningServiceTest {
    @Test
    void releaseCompletedTurnsValidatedCompatibilityFeedbackIntoLessonCandidate() {
        var events = mock(DomainEventService.class);
        var candidates = mock(MemoryCandidateService.class);
        var sessions = mock(PrdSessionRepository.class);
        var revision = event("evt-revision", "RevisionRequested",
                "{\"revision\":1,\"phase\":\"merge\",\"note\":\"接口字段必须保持向后兼容\"}");
        var release = event("evt-release", "ReleaseCompleted", "{}");
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of(revision, release));
        var service = new WorkItemMemoryLearningService(events, sessions,
                new PrdDraftCodec(new ObjectMapper()), candidates, new ObjectMapper());

        service.learn(release);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(List.class);
        verify(candidates).createAll(captor.capture());
        assertThat((List<MemoryCandidateService.CandidateInput>) captor.getValue()).singleElement().satisfies(input -> {
            assertThat(input.category()).isEqualTo("lesson");
            assertThat(input.audience()).isEqualTo("execution");
            assertThat(input.content()).isEqualTo("接口字段必须保持向后兼容");
            assertThat(input.sourceRef()).isEqualTo("work-item:wi-1:evt-revision");
            assertThat(input.evidenceRefs()).containsExactly("evt-revision", "evt-release");
        });
    }

    private DomainEventRecord event(String eventId, String type, String payload) {
        return new DomainEventRecord(1L, eventId, type, "v5.0", "sys-1", "case-1", null,
                "wi-1", "worker", "worker", payload, "case-1", null, type + "-1", Instant.now());
    }
}
