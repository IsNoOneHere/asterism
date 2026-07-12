package com.agentteam.v5.system;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SystemConfigLock {
    private final JdbcClient jdbc;

    public SystemConfigLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockBusinessModels(String systemId) {
        // 同一系统的模型池写操作串行执行，事务结束后 PostgreSQL 自动释放锁。
        jdbc.sql("select pg_advisory_xact_lock(hashtext('v5-business-models'), hashtext(:systemId))")
                .param("systemId", systemId)
                .query((rs, rowNum) -> true)
                .single();
    }
}
