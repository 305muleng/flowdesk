package com.flowdesk.interceptor;

import com.flowdesk.common.Result;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.util.ResponseUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoginInterceptor(JwtUtil jwtUtil, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        UserContext.remove();

        String authorization = request.getHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")) {

            ResponseUtil.writeErrorResponse(
                    response,
                    401,
                    "未登录"
            );

            return false;
        }

        String token = authorization.substring(7);

        Claims claims;
        Long userId;

        // 第一阶段：只负责验证 Token
        try {
            claims = jwtUtil.parseToken(token);
            userId = Long.valueOf(claims.getSubject());
        } catch (Exception e) {

            ResponseUtil.writeErrorResponse(
                    response,
                    401,
                    "登录已过期或Token无效"
            );

            return false;
        }

        // 第二阶段：查询数据库中的当前用户
        User user = userMapper.selectById(userId);

        if (user == null) {

            ResponseUtil.writeErrorResponse(
                    response,
                    401,
                    "登录已失效"
            );

            return false;
        }

        if (!"ACTIVE".equals(user.getStatus())) {

            ResponseUtil.writeErrorResponse(
                    response,
                    403,
                    "账号已被禁用"
            );

            return false;
        }

        // 使用数据库里的最新角色，而不是 JWT 里的旧角色
        CurrentUser currentUser =
                new CurrentUser(
                        user.getId(),
                        user.getSystemRole()
                );

        UserContext.set(currentUser);

        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {

        UserContext.remove();
    }
}
