package com.flowdesk.controller;

import com.flowdesk.common.PageResult;
import com.flowdesk.common.Result;
import com.flowdesk.service.AdminUserService;
import com.flowdesk.vo.AdminUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "管理员用户管理",
        description = "系统管理员查看和管理系统用户"
)
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(
            AdminUserService adminUserService) {

        this.adminUserService = adminUserService;
    }

    @Operation(summary = "查看用户列表")
    @GetMapping
    public Result<PageResult<AdminUserVO>> getUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String systemRole) {

        return Result.success(
                adminUserService.getUsers(
                        page,
                        size,
                        keyword,
                        status,
                        systemRole
                )
        );
    }

    @Operation(summary = "禁用用户")
    @PutMapping("/{userId}/disable")
    public Result<Void> disableUser(
            @PathVariable Long userId) {

        adminUserService.disableUser(userId);

        return Result.success(null);
    }

    @Operation(summary = "恢复用户")
    @PutMapping("/{userId}/enable")
    public Result<Void> enableUser(
            @PathVariable Long userId) {

        adminUserService.enableUser(userId);

        return Result.success(null);
    }
}