package com.flowdesk.interceptor;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.util.ResponseUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class SystemAdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        CurrentUser currentUser = UserContext.get();

        if (currentUser == null) {

            ResponseUtil.writeErrorResponse(
                    response,
                    401,
                    "未登录"
            );

            return false;
        }

        if (!"SYSTEM_ADMIN".equals(
                currentUser.getSystemRole())) {

            ResponseUtil.writeErrorResponse(
                    response,
                    403,
                    "无系统管理员权限"
            );

            return false;
        }

        return true;
    }
}