package com.flowdesk.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.flowdesk.enums.*;
import org.apache.ibatis.type.EnumTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public ConfigurationCustomizer statusTypeHandlers() {
        return configuration -> {
            // These status names are persisted codes: keep names stable when refactoring enums.
            // Explicit registration also overrides MyBatis-Plus IEnum auto-mapping for strict reads.
            var registry = configuration.getTypeHandlerRegistry();
            registry.register(ProjectStatus.class, new EnumTypeHandler<>(ProjectStatus.class));
            registry.register(TaskStatus.class, new EnumTypeHandler<>(TaskStatus.class));
            registry.register(ProjectAcceptanceStatus.class, new EnumTypeHandler<>(ProjectAcceptanceStatus.class));
            registry.register(TaskSubmissionStatus.class, new EnumTypeHandler<>(TaskSubmissionStatus.class));
            registry.register(ProjectInvitationStatus.class, new EnumTypeHandler<>(ProjectInvitationStatus.class));
            registry.register(TaskRequestStatus.class, new EnumTypeHandler<>(TaskRequestStatus.class));
            registry.register(MemberLeaveRequestStatus.class, new EnumTypeHandler<>(MemberLeaveRequestStatus.class));
            registry.register(ProjectManagerTransferStatus.class, new EnumTypeHandler<>(ProjectManagerTransferStatus.class));
            registry.register(UserStatus.class, new EnumTypeHandler<>(UserStatus.class));
            registry.register(ProjectMemberStatus.class, new EnumTypeHandler<>(ProjectMemberStatus.class));
        };
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
