package com.agentteam.v5.identity;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class SystemAccessService {
    private final SystemMembershipRepository memberships;

    public SystemAccessService(SystemMembershipRepository memberships) {
        this.memberships = memberships;
    }

    public void requireMember(String systemId, Authentication actor) {
        if (!canAccess(systemId, actor)) {
            throw new AccessDeniedException("非系统成员无权访问");
        }
    }

    public void requireOwnerOrAdmin(String systemId, Authentication actor) {
        if (!canControl(systemId, actor)) {
            throw new AccessDeniedException("非系统 owner/admin 无权操作");
        }
    }

    public boolean isAdmin(Authentication actor) {
        return actor.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    public boolean canAccess(String systemId, Authentication actor) {
        return isAdmin(actor) || memberships.countMemberships(systemId, actor.getName()) > 0;
    }

    public boolean canControl(String systemId, Authentication actor) {
        return isAdmin(actor) || memberships.countOwnerOrAdminMemberships(systemId, actor.getName()) > 0;
    }
}
