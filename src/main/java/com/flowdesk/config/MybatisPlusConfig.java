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
            // 这些状态的枚举名称就是数据库编码，重构时必须保持名称稳定。
            // 显式注册以覆盖 MyBatis-Plus 的 IEnum 自动映射，确保读取非法编码时报错。
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
