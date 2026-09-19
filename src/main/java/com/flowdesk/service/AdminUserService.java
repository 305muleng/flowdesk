package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.PageResult;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.vo.AdminUserVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserService {

    private final UserMapper userMapper;

    public AdminUserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public PageResult<AdminUserVO> getUsers(
            long page,
            long size,
            String keyword,
            String status,
            String systemRole) {

        if (page < 1) {
            throw new BusinessException(400, "page 不能小于 1");
        }

        if (size < 1 || size > 100) {
            throw new BusinessException(400, "size 必须在 1 到 100 之间");
        }

        if (status != null
                && !status.isBlank()
                && !"ACTIVE".equals(status)
                && !"DISABLED".equals(status)) {

            throw new BusinessException(
                    400,
                    "status 只能是 ACTIVE 或 DISABLED"
            );
        }

        if (systemRole != null
                && !systemRole.isBlank()
                && !"USER".equals(systemRole)
                && !"SYSTEM_ADMIN".equals(systemRole)) {

            throw new BusinessException(
                    400,
                    "systemRole 只能是 USER 或 SYSTEM_ADMIN"
            );
        }

        Page<User> userPage =
                new Page<>(page, size);

        LambdaQueryWrapper<User> wrapper =
                new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isBlank()) {

            wrapper.and(w ->
                    w.like(
                            User::getUsername,
                            keyword.trim()
                    ).or().like(
                            User::getRealName,
                            keyword.trim()
                    )
            );
        }

        if (status != null && !status.isBlank()) {

            wrapper.eq(
                    User::getStatus,
                    status.trim()
            );
        }

        if (systemRole != null
                && !systemRole.isBlank()) {

            wrapper.eq(
                    User::getSystemRole,
                    systemRole.trim()
            );
        }

        wrapper.orderByDesc(
                User::getCreatedAt
        );

        userMapper.selectPage(
                userPage,
                wrapper
        );

        List<AdminUserVO> records =
                userPage.getRecords()
                        .stream()
                        .map(this::toVO)
                        .toList();

        return new PageResult<>(
                records,
                userPage.getTotal(),
                userPage.getCurrent(),
                userPage.getSize(),
                userPage.getPages()
        );
    }

    public void disableUser(Long userId) {

        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(
                    404,
                    "用户不存在"
            );
        }

        Long currentUserId =
                UserContext.get().getUserId();

        if (currentUserId.equals(userId)) {
            throw new BusinessException(
                    409,
                    "不能禁用自己的账号"
            );
        }

        if ("DISABLED".equals(user.getStatus())) {
            return;
        }

        user.setStatus("DISABLED");

        userMapper.updateById(user);
    }

    public void enableUser(Long userId) {

        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(
                    404,
                    "用户不存在"
            );
        }

        if ("ACTIVE".equals(user.getStatus())) {
            return;
        }

        user.setStatus("ACTIVE");

        userMapper.updateById(user);
    }

    private AdminUserVO toVO(User user) {

        AdminUserVO vo = new AdminUserVO();

        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setSystemRole(user.getSystemRole());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());

        return vo;
    }
}