package com.flowdesk.config;

import com.flowdesk.interceptor.LoginInterceptor;
import com.flowdesk.interceptor.SystemAdminInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final SystemAdminInterceptor systemAdminInterceptor;

    public WebConfig(
            LoginInterceptor loginInterceptor,
            SystemAdminInterceptor systemAdminInterceptor) {

        this.loginInterceptor = loginInterceptor;
        this.systemAdminInterceptor = systemAdminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/users/login",
                        "/users/register",

                        "/swagger-ui.html",
                        "/swagger-ui/**",

                        "/v3/api-docs",
                        "/v3/api-docs/**"
                );

        registry.addInterceptor(systemAdminInterceptor)
                .addPathPatterns("/admin/**");
    }
}