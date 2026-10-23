package com.flowdesk.service;

import com.flowdesk.enums.UserStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.LoginDTO;
import com.flowdesk.dto.RegisterDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.validation.PasswordRules;
import com.flowdesk.vo.LoginVO;
import com.flowdesk.vo.UserProfileVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Long register(RegisterDTO dto) {
        String username = dto.getUsername().trim();

        if (username.length() < 3) {
            throw new BusinessException(400, "用户名长度必须在3到50个字符之间");
        }

        if (!PasswordRules.isWithinBcryptLimit(dto.getPassword())) {
            throw new BusinessException(400, PasswordRules.BCRYPT_LENGTH_MESSAGE);
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        if (count > 0) {
            throw new BusinessException(409, "用户名已存在");
        }

        User user = new User();

        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName().trim());
        user.setSystemRole("USER");
        user.setStatus(UserStatus.ACTIVE);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            if (isUsernameConflict(e)) {
                throw new BusinessException(409, "用户名已存在");
            }
            throw e;
        }

        return user.getId();
    }

    public LoginVO login(LoginDTO dto) {
        if (!PasswordRules.isWithinBcryptLimit(dto.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        String username = dto.getUsername().trim();

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        boolean passwordCorrect = passwordEncoder.matches(dto.getPassword(), user.getPasswordHash());

        if (!passwordCorrect) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (!UserStatus.ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        String token = jwtUtil.generateToken(user.getId());

        LoginVO loginVO = new LoginVO();

        loginVO.setUserId(user.getId());
        loginVO.setUsername(user.getUsername());
        loginVO.setRealName(user.getRealName());
        loginVO.setSystemRole(user.getSystemRole());
        loginVO.setToken(token);

        return loginVO;
    }

    public UserProfileVO getCurrentUserProfile() {
        User user = userMapper.selectById(UserContext.get().getUserId());

        if (user == null) {
            throw new BusinessException(401, "登录用户不存在");
        }

        UserProfileVO profile = new UserProfileVO();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setRealName(user.getRealName());
        profile.setSystemRole(user.getSystemRole());
        profile.setStatus(user.getStatus() == null ? null : user.getStatus().getCode());
        profile.setCreatedAt(user.getCreatedAt());
        profile.setUpdatedAt(user.getUpdatedAt());
        return profile;
    }

    private boolean isUsernameConflict(DuplicateKeyException exception) {
        Throwable cause = exception.getMostSpecificCause();

        if (!(cause instanceof SQLException sqlException)
                || sqlException.getErrorCode() != 1062) {
            return false;
        }

        String message = sqlException.getMessage();
        if (message == null) {
            return false;
        }

        return message.endsWith("for key 'uk_user_username'")
                || message.endsWith("for key 'user.uk_user_username'");
    }
}
