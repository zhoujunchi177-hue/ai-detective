package com.mindtrace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "用户名需为 3-20 位字母、数字或下划线")
            String username,
            @NotBlank(message = "昵称不能为空")
            @Size(max = 24, message = "昵称最多 24 个字符")
            String nickname,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需为 6-32 位")
            String password) {
    }

    public record AuthResponse(String token, UserDtos.Profile profile) {
    }
}

