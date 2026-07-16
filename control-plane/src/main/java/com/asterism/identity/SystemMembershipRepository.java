package com.asterism.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SystemMembershipRepository {
    private final JdbcClient jdbc;

    public SystemMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long countMemberships(String systemId, String userId) {
        return count("""
                select count(*)
                from system_memberships
                where system_id = :systemId
                  and user_id = :userId
                """, systemId, userId);
    }

    public long countOwnerOrAdminMemberships(String systemId, String userId) {
        return count("""
                select count(*)
                from system_memberships
                where system_id = :systemId
                  and user_id = :userId
                  and role in ('owner', 'admin')
                """, systemId, userId);
    }

    public List<String> findSystemIdsForUser(String userId) {
        return jdbc.sql("""
                        select distinct system_id
                        from system_memberships
                        where user_id = :userId
                        order by system_id
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public List<SystemMemberView> listMembers(String systemId) {
        return jdbc.sql("""
                        select m.system_id, m.user_id, u.display_name, m.role, m.created_at
                        from system_memberships m
                        left join users u on u.user_id = m.user_id
                        where m.system_id = :systemId
                        order by m.role, m.user_id
                        """)
                .param("systemId", systemId)
                .query((rs, rowNum) -> new SystemMemberView(
                        rs.getString("system_id"),
                        rs.getString("user_id"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void upsertMembership(String systemId, String userId, String role, String actor) {
        jdbc.sql("""
                        insert into system_memberships(system_id, user_id, role, created_by)
                        values (:systemId, :userId, :role, :actor)
                        on conflict (system_id, user_id, role) do nothing
                        """)
                .param("systemId", systemId)
                .param("userId", userId)
                .param("role", role)
                .param("actor", actor)
                .update();
    }

    public void deleteMembership(String systemId, String userId, String role) {
        jdbc.sql("""
                        delete from system_memberships
                        where system_id = :systemId and user_id = :userId and role = :role
                        """)
                .param("systemId", systemId)
                .param("userId", userId)
                .param("role", role)
                .update();
    }

    public void deleteMembershipsForSystem(String systemId) {
        jdbc.sql("delete from system_memberships where system_id = :systemId")
                .param("systemId", systemId)
                .update();
    }

    public void deleteMembershipsForUser(String userId) {
        jdbc.sql("delete from system_memberships where user_id = :userId")
                .param("userId", userId)
                .update();
    }

    public long countOwners(String systemId) {
        return jdbc.sql("""
                        select count(*)
                        from system_memberships
                        where system_id = :systemId and role = 'owner'
                        """)
                .param("systemId", systemId)
                .query(Long.class)
                .single();
    }

    private long count(String sql, String systemId, String userId) {
        return jdbc.sql(sql)
                .param("systemId", systemId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    public record SystemMemberView(String systemId, String userId, String displayName, String role,
                                   java.time.Instant createdAt) {
    }
}
