package com.agentteam.v5.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class WorkerTokenAuthenticationFilter extends OncePerRequestFilter {
    private final WorkerCallbackProperties properties;

    public WorkerTokenAuthenticationFilter(WorkerCallbackProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var token = request.getHeader("X-Agent-Team-Worker-Token");
        var authorization = request.getHeader("Authorization");
        if (token == null && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length());
        }
        if (!properties.requiredToken().equals(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Worker token 无效\",\"details\":null}");
            return;
        }
        // worker service 账号只用于回调通道，不复用前端用户 session。
        var auth = new UsernamePasswordAuthenticationToken(
                "agent-team-worker",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
