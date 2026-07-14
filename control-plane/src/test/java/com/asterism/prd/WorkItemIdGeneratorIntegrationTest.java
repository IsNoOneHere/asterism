package com.asterism.prd;

import com.asterism.IntegrationDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class WorkItemIdGeneratorIntegrationTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), SHANGHAI);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired
    private JdbcClient jdbc;

    @Test
    void generatesDateAndFourDigitRandomSuffix() {
        var generator = new WorkItemIdGenerator(jdbc, FIXED_CLOCK, ascendingSuffixes());

        generator.lockAllocation();
        var workItemId = generator.nextId();

        assertThat(workItemId).matches("^WI20990101[0-9]{4}$");
        assertThat(Integer.parseInt(workItemId.substring(10))).isBetween(1000, 9999);
    }

    @Test
    void rejectsRecentOneHundredSuffixesAndAllowsOlderSuffix() {
        for (var suffix = 1000; suffix <= 1100; suffix++) {
            appendConfirmedEvent("WI20980101" + suffix);
        }
        var attempts = new AtomicInteger();
        IntSupplier suffixes = () -> attempts.getAndIncrement() == 0 ? 1001 : 1000;
        var generator = new WorkItemIdGenerator(jdbc, FIXED_CLOCK, suffixes);

        generator.lockAllocation();
        var workItemId = generator.nextId();

        assertThat(workItemId).isEqualTo("WI209901011000");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void rejectsExistingFullWorkItemIdOutsideRecentWindow() {
        var available = availableSuffixes();
        var existingSuffix = available[0];
        var nextSuffix = available[1];
        insertPrdSession("WI20990101" + existingSuffix);
        var attempts = new AtomicInteger();
        IntSupplier suffixes = () -> attempts.getAndIncrement() == 0 ? existingSuffix : nextSuffix;
        var generator = new WorkItemIdGenerator(jdbc, FIXED_CLOCK, suffixes);

        generator.lockAllocation();
        var workItemId = generator.nextId();

        assertThat(workItemId).isEqualTo("WI20990101" + nextSuffix);
        assertThat(attempts).hasValue(2);
    }

    private void appendConfirmedEvent(String workItemId) {
        jdbc.sql("""
                        insert into domain_events(event_id, event_type, schema_version, work_item_id, source)
                        values (:eventId, 'PRDConfirmed', 'v5.0', :workItemId, 'test')
                        """)
                .param("eventId", "evt-test-" + UUID.randomUUID())
                .param("workItemId", workItemId)
                .update();
    }

    private void insertPrdSession(String workItemId) {
        var prdId = "prd-test-" + UUID.randomUUID();
        jdbc.sql("""
                        insert into prd_sessions(
                            prd_id, system_id, conversation_id, work_item_id, case_id, title, goal,
                            status, created_by, confirmed_by, confirmed_at
                        ) values (
                            :prdId, 'demo-system', :conversationId, :workItemId, :caseId, 'test', 'test',
                            'case_start_failed', 'test', 'test', now()
                        )
                        """)
                .param("prdId", prdId)
                .param("conversationId", "conv-" + prdId)
                .param("workItemId", workItemId)
                .param("caseId", "case-" + prdId)
                .update();
    }

    private int[] availableSuffixes() {
        var recent = new HashSet<>(jdbc.sql("""
                        select right(work_item_id, 4)
                        from domain_events
                        where event_type = 'PRDConfirmed'
                          and work_item_id ~ '^WI[0-9]{12}$'
                        order by sequence desc
                        limit 100
                        """)
                .query(String.class)
                .list());
        return java.util.stream.IntStream.range(1000, 10_000)
                .filter(value -> !recent.contains(String.valueOf(value)))
                .limit(2)
                .toArray();
    }

    private IntSupplier ascendingSuffixes() {
        var current = new AtomicInteger(1000);
        return () -> 1000 + Math.floorMod(current.getAndIncrement() - 1000, 9000);
    }
}
