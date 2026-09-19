package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.LoginDTO;
import com.flowdesk.dto.RegisterDTO;
import com.flowdesk.service.UserService;
import com.flowdesk.vo.LoginVO;
import com.flowdesk.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "用户认证",
        description = "用户注册与登录接口"
)
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "用户注册",
            description = "创建 FlowDesk 用户账号"
    )
    @SecurityRequirements
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterDTO dto) {
        Long userId = userService.register(dto);
        return Result.success("注册成功", userId);
    }

    @Operation(
            summary = "用户登录",
            description = "校验用户名和密码，登录成功后返回 JWT"
    )
    @SecurityRequirements
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        LoginVO loginVO = userService.login(dto);
        return Result.success("登录成功", loginVO);
    }

    @Operation(
            summary = "获取当前登录用户",
            description = "根据当前请求中的 JWT 获取已登录用户的基本身份信息"
    )
    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        return Result.success(userService.getCurrentUserProfile());
    }
}
