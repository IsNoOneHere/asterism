package com.asterism.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class JdbcUserAccountService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(JdbcUserAccountService.class);
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final SystemMembershipRepository memberships;

    public JdbcUserAccountService(JdbcClient jdbc, PasswordEncoder encoder, SystemMembershipRepository memberships) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.memberships = memberships;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var row = jdbc.sql("""
                        select user_id, password_hash, enabled
                        from users
                        where user_id = :userId
                        """)
                .param("userId", username)
                .query((rs, rowNum) -> new UserRow(rs.getString("user_id"), rs.getString("password_hash"), rs.getBoolean("enabled")))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException(username));
        var builder = User.withUsername(row.userId()).password(row.passwordHash()).disabled(!row.enabled());
        return "admin".equals(row.userId()) ? builder.roles("ADMIN").build() : builder.roles("USER").build();
    }

    public List<UserAccountView> listUsers() {
        return jdbc.sql("""
                        select user_id, display_name, email, enabled
                        from users
                        order by user_id
                        """)
                .query((rs, rowNum) -> new UserAccountView(
                        rs.getString("user_id"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getBoolean("enabled")))
                .list();
    }

    public UserAccountView upsertUser(String userId, String displayName, String email, String password) {
        // 不传密码时只允许更新已有账号，生产不再创建固定默认凭证。
        if (password == null || password.isBlank()) {
            var enabled = jdbc.sql("""
                            update users set display_name = :displayName, email = :email, updated_at = now()
                            where user_id = :userId
                            returning enabled
                            """)
                    .param("userId", userId)
                    .param("displayName", displayName)
                    .param("email", email)
                    .query(Boolean.class)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("新用户必须设置初始密码"));
            return new UserAccountView(userId, displayName, email, enabled);
        }
        // 密码只写 BCrypt hash，不对外返回。
        var enabled = jdbc.sql("""
                        insert into users(user_id, display_name, email, password_hash, enabled, created_by, updated_at)
                        values (:userId, :displayName, :email, :passwordHash, true, current_user, now())
                        on conflict (user_id) do update
                        set display_name = excluded.display_name,
                            email = excluded.email,
                            password_hash = excluded.password_hash,
                            updated_at = now()
                        returning enabled
                        """)
                .param("userId", userId)
                .param("displayName", displayName)
                .param("email", email)
                .param("passwordHash", encoder.encode(password))
                .query(Boolean.class)
                .single();
        return new UserAccountView(userId, displayName, email, enabled);
    }

    public void disableUser(String userId, String actor) {
        if (userId.equals(actor)) throw new IllegalStateException("不能禁用当前登录用户");
        setEnabled(userId, false);
        log.info("用户已禁用 user={} actor={}", userId, actor);
    }

    public void enableUser(String userId, String actor) {
        setEnabled(userId, true);
        log.info("用户已启用 user={} actor={}", userId, actor);
    }

    private void setEnabled(String userId, boolean enabled) {
        var updated = jdbc.sql("update users set enabled = :enabled, updated_at = now() where user_id = :userId")
                .param("userId", userId)
                .param("enabled", enabled)
                .update();
        if (updated == 0) throw new IllegalArgumentException("用户不存在");
    }

    public void resetPassword(String userId, String password) {
        jdbc.sql("update users set password_hash = :passwordHash, updated_at = now() where user_id = :userId")
                .param("userId", userId)
                .param("passwordHash", encoder.encode(password))
                .update();
    }

    @Transactional
    public void deleteUser(String userId, String actor) {
        if (userId.equals(actor)) throw new IllegalStateException("不能删除当前登录用户");
        var ownsSystem = jdbc.sql("select exists (select 1 from systems where owner_user_id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
        if (ownsSystem) throw new IllegalStateException("用户仍是系统负责人，请先转移负责人");
        try {
            // 账号删除时同步清理成员角色，历史业务审计仍保留原用户 ID。
            memberships.deleteMembershipsForUser(userId);
            var deleted = jdbc.sql("delete from users where user_id = :userId")
                    .param("userId", userId)
                    .update();
            if (deleted == 0) throw new IllegalArgumentException("用户不存在");
        } catch (DataIntegrityViolationException error) {
            throw new IllegalStateException("用户仍被业务数据引用，无法删除", error);
        }
        log.info("用户已删除 user={} actor={}", userId, actor);
    }

    public void upsertMembership(String systemId, String userId, String role, String actor) {
        memberships.upsertMembership(systemId, userId, role, actor);
    }

    private record UserRow(String userId, String passwordHash, boolean enabled) {
    }
}
