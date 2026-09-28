package com.mindtrace.controller;

import com.mindtrace.common.ApiResponse;
import com.mindtrace.dto.UserDtos;
import com.mindtrace.security.UserContext;
import com.mindtrace.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<UserDtos.Profile> profile() {
        return ApiResponse.ok(userService.profile(UserContext.userId()));
    }
}

