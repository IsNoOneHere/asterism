package com.asterism.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JdbcUserAccountService implements UserDetailsService {
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
            var updated = jdbc.sql("""
                            update users set display_name = :displayName, email = :email,
                                enabled = true, updated_at = now()
                            where user_id = :userId
                            """)
                    .param("userId", userId)
                    .param("displayName", displayName)
                    .param("email", email)
                    .update();
            if (updated == 0) throw new IllegalArgumentException("新用户必须设置初始密码");
            return new UserAccountView(userId, displayName, email, true);
        }
        // 密码只写 BCrypt hash，不对外返回。
        jdbc.sql("""
                        insert into users(user_id, display_name, email, password_hash, enabled, created_by, updated_at)
                        values (:userId, :displayName, :email, :passwordHash, true, current_user, now())
                        on conflict (user_id) do update
                        set display_name = excluded.display_name,
                            email = excluded.email,
                            password_hash = excluded.password_hash,
                            enabled = true,
                            updated_at = now()
                        """)
                .param("userId", userId)
                .param("displayName", displayName)
                .param("email", email)
                .param("passwordHash", encoder.encode(password))
                .update();
        return new UserAccountView(userId, displayName, email, true);
    }

    public void disableUser(String userId) {
        jdbc.sql("update users set enabled = false, updated_at = now() where user_id = :userId")
                .param("userId", userId)
                .update();
    }

    public void resetPassword(String userId, String password) {
        jdbc.sql("update users set password_hash = :passwordHash, updated_at = now() where user_id = :userId")
                .param("userId", userId)
                .param("passwordHash", encoder.encode(password))
                .update();
    }

    public void upsertMembership(String systemId, String userId, String role, String actor) {
        memberships.upsertMembership(systemId, userId, role, actor);
    }

    private record UserRow(String userId, String passwordHash, boolean enabled) {
    }
}
