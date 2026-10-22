package com.flowdesk.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.flowdesk.enums.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public ConfigurationCustomizer statusTypeHandlers() {
        return configuration -> {
            var registry = configuration.getTypeHandlerRegistry();
            registry.register(ProjectStatus.class, new StatusCodeTypeHandler<>(ProjectStatus.class));
            registry.register(TaskStatus.class, new StatusCodeTypeHandler<>(TaskStatus.class));
            registry.register(ProjectAcceptanceStatus.class, new StatusCodeTypeHandler<>(ProjectAcceptanceStatus.class));
            registry.register(TaskSubmissionStatus.class, new StatusCodeTypeHandler<>(TaskSubmissionStatus.class));
            registry.register(ProjectInvitationStatus.class, new StatusCodeTypeHandler<>(ProjectInvitationStatus.class));
            registry.register(TaskRequestStatus.class, new StatusCodeTypeHandler<>(TaskRequestStatus.class));
            registry.register(MemberLeaveRequestStatus.class, new StatusCodeTypeHandler<>(MemberLeaveRequestStatus.class));
            registry.register(
                ProjectManagerTransferStatus.class,
                new StatusCodeTypeHandler<>(ProjectManagerTransferStatus.class)
            );
            registry.register(UserStatus.class, new StatusCodeTypeHandler<>(UserStatus.class));
            registry.register(ProjectMemberStatus.class, new StatusCodeTypeHandler<>(ProjectMemberStatus.class));
        };
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
