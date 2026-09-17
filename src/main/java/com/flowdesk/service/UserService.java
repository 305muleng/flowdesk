package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.dto.LoginDTO;
import com.flowdesk.dto.RegisterDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.vo.LoginVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Long register(RegisterDTO dto) {

        String username = dto.getUsername().trim();

        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );

        if (count > 0) {
            throw new BusinessException(409, "用户名已存在");
        }

        User user = new User();

        user.setUsername(username);
        user.setPasswordHash(
                passwordEncoder.encode(dto.getPassword())
        );
        user.setRealName(dto.getRealName().trim());
        user.setSystemRole("USER");
        user.setStatus("ACTIVE");

        userMapper.insert(user);

        return user.getId();
    }

    public LoginVO login(LoginDTO dto) {

        String username = dto.getUsername().trim();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );

        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        boolean passwordCorrect = passwordEncoder.matches(
                dto.getPassword(),
                user.getPasswordHash()
        );

        if (!passwordCorrect) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
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
}