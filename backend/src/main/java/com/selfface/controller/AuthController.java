package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.entity.User;
import com.selfface.security.CurrentUser;
import com.selfface.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    public record RegisterRequest(
            @NotBlank(message = "不能为空") @Size(min = 3, max = 32, message = "长度需在 3-32 之间") String username,
            @NotBlank(message = "不能为空") @Size(min = 6, max = 64, message = "至少 6 位") String password,
            String nickname,
            String email) {
    }

    public record LoginRequest(@NotBlank(message = "不能为空") String username,
                               @NotBlank(message = "不能为空") String password) {
    }

    public record ProfileRequest(String nickname, String email, String targetCities, String targetPosition) {
    }

    @PostMapping("/register")
    public R<AuthService.AuthResult> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(authService.register(req.username(), req.password(), req.nickname(), req.email()));
    }

    @PostMapping("/login")
    public R<AuthService.AuthResult> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public R<User> me() {
        return R.ok(authService.requireUser(CurrentUser.id()));
    }

    @PutMapping("/profile")
    public R<User> updateProfile(@RequestBody ProfileRequest req) {
        return R.ok(authService.updateProfile(CurrentUser.id(), req.nickname(), req.email(),
                req.targetCities(), req.targetPosition()));
    }
}
