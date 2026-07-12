package com.agentteam.v5;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

public final class IntegrationDatabase {
    private static final String LOCAL_URL = "jdbc:postgresql://localhost:55432/agent_team_v5?stringtype=unspecified&currentSchema=control_plane_v5,public";
    private static final String USER = "agent_team";
    private static final String PASSWORD = "agent_team";
    private static PostgreSQLContainer<?> postgres;

    private IntegrationDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        var configuredUrl = System.getProperty("v5.test.db.url");
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            register(registry, configuredUrl,
                    System.getProperty("v5.test.db.user", USER),
                    System.getProperty("v5.test.db.password", PASSWORD));
            return;
        }
        if (canUse(LOCAL_URL)) {
            register(registry, LOCAL_URL, USER, PASSWORD);
            return;
        }
        // ponytail: fallback keeps the requested Testcontainers path for CI without assuming local compose.
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("agent_team_v5")
                .withUsername(USER)
                .withPassword(PASSWORD);
        postgres.start();
        register(registry, postgres.getJdbcUrl() + "&stringtype=unspecified&currentSchema=control_plane_v5,public",
                postgres.getUsername(), postgres.getPassword());
    }

    private static void register(DynamicPropertyRegistry registry, String url, String user, String password) {
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
    }

    private static boolean canUse(String url) {
        try (var ignored = DriverManager.getConnection(url, USER, PASSWORD)) {
            return true;
        } catch (SQLException error) {
            return false;
        }
    }
}
