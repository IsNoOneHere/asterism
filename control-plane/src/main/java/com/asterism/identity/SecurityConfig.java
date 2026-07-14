package com.asterism.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain workerCallbackSecurity(HttpSecurity http, WorkerCallbackProperties properties) throws Exception {
        // worker 回调通道使用独立 token，不能 permitAll，也不依赖前端 session。
        return http
                .securityMatcher(request -> {
                    var path = request.getRequestURI();
                    return path.startsWith("/api/v5/projections")
                            || ("POST".equals(request.getMethod()) && Set.of("/api/v5/context-snapshots").contains(path))
                            || path.startsWith("/api/v5/internal/");
                })
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new WorkerTokenAuthenticationFilter(properties), BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // 本地工作台先用 session + Basic；后续接正式登录页时仍复用 Spring Security。
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/healthz", "/api/v5/auth/login", "/api/v5/openapi.json", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(withDefaults())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "未登录或登录已过期")))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v5/auth/login")
                        // SPA 用 fetch 登录，成功不跳转，失败也只返回明确状态码。
                        .successHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, exception) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误")))
                .logout(logout -> logout
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":null}");
    }

}
