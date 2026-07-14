package com.asterism.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v5/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private final JdbcUserAccountService users;

    public UserAdminController(JdbcUserAccountService users) {
        this.users = users;
    }

    @GetMapping
    Iterable<UserAccountView> list() {
        return users.listUsers();
    }

    @PostMapping
    UserAccountView upsert(@Valid @RequestBody UpsertUserRequest request) {
        return users.upsertUser(request.userId(), request.displayName(), request.email(), request.password());
    }

    @PostMapping("/{userId}/disable")
    void disable(@PathVariable String userId) {
        users.disableUser(userId);
    }

    @PostMapping("/{userId}/reset-password")
    void resetPassword(@PathVariable String userId, @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(userId, request.password());
    }

    @PostMapping("/memberships")
    void upsertMembership(@Valid @RequestBody MembershipRequest request, Authentication actor) {
        users.upsertMembership(request.systemId(), request.userId(), request.role(), actor.getName());
    }

    public record UpsertUserRequest(@NotBlank String userId, @NotBlank String displayName, String email, String password) {
    }

    public record ResetPasswordRequest(@NotBlank String password) {
    }

    public record MembershipRequest(@NotBlank String systemId, @NotBlank String userId, @NotBlank String role) {
    }
}

