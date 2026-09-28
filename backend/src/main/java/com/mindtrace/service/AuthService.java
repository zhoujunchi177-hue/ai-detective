package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.AuthDtos;
import com.mindtrace.entity.User;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.UserMapper;
import com.mindtrace.security.JwtService;
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
    private final JwtService jwtService;
    private final UserService userService;

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = request.username().trim();
        Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setNickname(request.nickname().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAvatar("avatar-" + ((Math.abs(username.hashCode()) % 6) + 1));
        user.setLevel(1);
        user.setExp(0);
        user.setCoins(100);
        user.setCompletedCases(0);
        user.setStreakDays(1);
        user.setTotalScore(0);
        user.setLastLoginAt(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        String token = jwtService.createToken(user.getId(), user.getUsername());
        return new AuthDtos.AuthResponse(token, userService.profile(user.getId()));
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.username().trim())
                .last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        String token = jwtService.createToken(user.getId(), user.getUsername());
        return new AuthDtos.AuthResponse(token, userService.profile(user.getId()));
    }
}

