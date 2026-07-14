package com.asterism.identity;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v5/auth")
public class AuthController {
    @GetMapping("/me")
    CurrentUser me(Authentication authentication) {
        var roles = authentication.getAuthorities().stream().map(Object::toString).toList();
        return new CurrentUser(authentication.getName(), roles);
    }

    public record CurrentUser(String userId, List<String> roles) {
    }
}

