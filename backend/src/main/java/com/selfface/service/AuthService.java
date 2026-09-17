package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.selfface.common.BizException;
import com.selfface.entity.User;
import com.selfface.mapper.UserMapper;
import com.selfface.security.JwtUtil;
import com.selfface.security.LoginAttemptGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptGuard loginAttemptGuard;

    public record AuthResult(String token, long expiresIn, User user) {
    }

    @Transactional
    public AuthResult register(String username, String rawPassword, String nickname, String email) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 3 || name.length() > 32) {
            throw new BizException("用户名长度需要在 3 到 32 个字符之间");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new BizException("密码至少 6 位");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        if (exists != null && exists > 0) {
            throw new BizException("用户名「" + name + "」已被占用");
        }

        User user = new User();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname == null || nickname.isBlank() ? name : nickname.trim());
        user.setEmail(email);
        user.setTargetCities("武汉,长沙,广州,成都,深圳");
        user.setTargetPosition("Java 后端开发实习生");
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        return buildResult(user);
    }

    public AuthResult login(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        // 账号处于锁定期就直接拒绝，连密码都不用比
        loginAttemptGuard.checkAllowed(name);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        // 用户不存在与密码错误返回同一句提示，避免暴露账号是否注册
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            // 用户名不存在时同样计数，否则能靠「是否被锁定」反推账号是否存在
            loginAttemptGuard.onFailure(name);
            throw new BizException(401, "用户名或密码错误");
        }
        loginAttemptGuard.onSuccess(name);
        return buildResult(user);
    }

    public User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "账号不存在或已被删除");
        }
        return user;
    }

    @Transactional
    public User updateProfile(Long userId, String nickname, String email, String targetCities, String targetPosition) {
        User user = requireUser(userId);
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname.trim());
        }
        if (email != null) {
            user.setEmail(email.trim());
        }
        if (targetCities != null) {
            user.setTargetCities(targetCities.trim());
        }
        if (targetPosition != null) {
            user.setTargetPosition(targetPosition.trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return user;
    }

    private AuthResult buildResult(User user) {
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        return new AuthResult(token, jwtUtil.getExpireSeconds(), user);
    }
}
