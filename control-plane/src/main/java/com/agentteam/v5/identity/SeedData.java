package com.agentteam.v5.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SeedData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedData.class);
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String configuredAdminPassword;
    private final boolean seedDemoData;
    private final String demoPassword;

    public SeedData(JdbcClient jdbc, PasswordEncoder encoder,
                    @Value("${agent-team.initial-admin-password:}") String configuredAdminPassword,
                    @Value("${agent-team.seed-demo-data:false}") boolean seedDemoData,
                    @Value("${agent-team.demo-password:}") String demoPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.configuredAdminPassword = configuredAdminPassword;
        this.seedDemoData = seedDemoData;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        var generated = configuredAdminPassword == null || configuredAdminPassword.isBlank();
        var adminPassword = generated ? randomPassword() : configuredAdminPassword;
        if (seedUser("admin", "Admin", adminPassword) > 0) {
            if (generated) log.warn("首次启动已创建 admin，临时密码={}", adminPassword);
            else log.info("首次启动已使用 V5_ADMIN_INITIAL_PASSWORD 创建 admin");
        }
        if (seedDemoData) seedDemoData();
    }

    private int seedUser(String userId, String displayName, String password) {
        // 启动种子只在账号缺失时写入，避免覆盖本地改过的密码。
        return jdbc.sql("""
                        insert into users(user_id, display_name, email, password_hash, enabled, created_by)
                        values (:userId, :displayName, :email, :passwordHash, true, 'seed')
                        on conflict (user_id) do nothing
                        """)
                .param("userId", userId)
                .param("displayName", displayName)
                .param("email", userId + "@local")
                .param("passwordHash", encoder.encode(password))
                .update();
    }

    private void seedDemoData() {
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalStateException("启用演示数据时必须显式设置 demo password");
        }
        // 固定测试密码只存在于 test resources，生产代码不再携带已知凭证。
        seedUser("e2e-user", "E2E User", demoPassword);
        seedUser("e2e-owner", "E2E Owner", demoPassword);
        seedSystem();
        seedMembership("demo-system", "e2e-user", "requester");
        seedMembership("demo-system", "e2e-owner", "owner");
        seedMembership("demo-system", "admin", "admin");
    }

    private String randomPassword() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void seedSystem() {
        jdbc.sql("""
                        insert into systems(system_id, name, description, repo_path, owner_user_id, created_by)
                        values ('demo-system', 'Demo System', '本地演示系统', '/tmp/demo-system', 'e2e-owner', 'seed')
                        on conflict (system_id) do nothing
                        """)
                .update();
    }

    private void seedMembership(String systemId, String userId, String role) {
        jdbc.sql("""
                        insert into system_memberships(system_id, user_id, role, created_by)
                        values (:systemId, :userId, :role, 'seed')
                        on conflict (system_id, user_id, role) do nothing
                        """)
                .param("systemId", systemId)
                .param("userId", userId)
                .param("role", role)
                .update();
    }
}
