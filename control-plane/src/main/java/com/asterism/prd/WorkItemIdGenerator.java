package com.asterism.prd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

@Component
public class WorkItemIdGenerator {
    private static final Logger log = LoggerFactory.getLogger(WorkItemIdGenerator.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long ALLOCATION_LOCK_KEY = 5_001L;
    private static final int RECENT_WINDOW_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IntSupplier randomSuffix;

    @Autowired
    public WorkItemIdGenerator(JdbcClient jdbc) {
        this(jdbc, Clock.system(SHANGHAI), () -> ThreadLocalRandom.current().nextInt(1000, 10_000));
    }

    WorkItemIdGenerator(JdbcClient jdbc, Clock clock, IntSupplier randomSuffix) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.randomSuffix = randomSuffix;
    }

    public void lockAllocation() {
        // 事务级 advisory lock 串行化多实例编号分配，事务结束后由 PostgreSQL 自动释放。
        jdbc.sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", ALLOCATION_LOCK_KEY)
                .query((rs, rowNum) -> true)
                .single();
    }

    public String nextId() {
        var prefix = "WI" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        var recentSuffixes = new HashSet<>(jdbc.sql("""
                        select right(work_item_id, 4)
                        from domain_events
                        where event_type = 'PRDConfirmed'
                          and work_item_id ~ '^WI[0-9]{12}$'
                        order by sequence desc
                        limit :windowSize
                        """)
                .param("windowSize", RECENT_WINDOW_SIZE)
                .query(String.class)
                .list());
        while (true) {
            var suffix = String.valueOf(randomSuffix.getAsInt());
            var candidate = prefix + suffix;
            if (!recentSuffixes.contains(suffix) && !exists(candidate)) {
                log.info("工作项编号已分配 workItemId={}", candidate);
                return candidate;
            }
        }
    }

    private boolean exists(String candidate) {
        return jdbc.sql("""
                        select exists (
                            select 1 from prd_sessions where work_item_id = :candidate
                            union all
                            select 1 from work_items where work_item_id = :candidate
                        )
                        """)
                .param("candidate", candidate)
                .query(Boolean.class)
                .single();
    }
}
