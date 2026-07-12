package com.agentteam.v5.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        // 独立健康检查控制器，确保 Spring MVC 能注册 /healthz 并触发安全白名单。
        jdbc.queryForObject("select 1", Integer.class);
        return Map.of("ok", true);
    }
}
