package com.flowdesk.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** 仅用于测试的环境保护：刷新上下文前校验配置，准备测试数据前核实实际连接目标。 */
public class IntegrationEnvironmentGuard extends AbstractTestExecutionListener
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validateSettings(context.getEnvironment()::getProperty);
    }

    static void validateSettings(Function<String, String> settings) {
        require("true".equalsIgnoreCase(settings.apply("flowdesk.test.redis-confirmed")),
                "Set FLOWDESK_TEST_REDIS_CONFIRMED=true only after confirming the dedicated test Redis target");
        String url = settings.apply("spring.datasource.url");
        require(url != null && url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3306/flowdesk_test(?:\\?.*)?"),
                "Integration tests require the local flowdesk_test database");
        require("flowdesk_test_user".equals(settings.apply("spring.datasource.username")),
                "Integration tests require flowdesk_test_user, never the application/admin account");
        String password = settings.apply("spring.datasource.password");
        require(password != null && !password.isBlank(), "FLOWDESK_TEST_DB_PASSWORD is required");
        String host = settings.apply("spring.data.redis.host");
        require("localhost".equals(host) || "127.0.0.1".equals(host), "Only local dedicated test Redis is allowed");
        require("15".equals(settings.apply("spring.data.redis.database")), "Only dedicated Redis DB15 is allowed");
        int port;
        try { port = Integer.parseInt(settings.apply("spring.data.redis.port")); }
        catch (RuntimeException e) { throw new IllegalStateException("Invalid test Redis port"); }
        require(port > 0 && port <= 65535, "Invalid test Redis port");
    }

    @Override
    public void beforeTestClass(TestContext testContext) throws Exception {
        var context = testContext.getApplicationContext();
        validateSettings(context.getEnvironment()::getProperty);
        for (DataSource source : context.getBeansOfType(DataSource.class).values()) {
            try (var connection = source.getConnection()) {
                connection.setReadOnly(true);
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                    require(result.next(), "Cannot verify actual test database");
                    validateDatabaseIdentity(result.getString(1), result.getString(2));
                }
            }
        }
        for (RedisConnectionFactory factory : context.getBeansOfType(RedisConnectionFactory.class).values()) {
            require(factory instanceof LettuceConnectionFactory, "Only standalone Lettuce test Redis is supported");
            var lettuce = (LettuceConnectionFactory) factory;
            require(lettuce.getStandaloneConfiguration() != null && lettuce.getDatabase() == 15,
                    "Actual Redis connection factory must select standalone DB15");
            String expectedHost = context.getEnvironment().getProperty("spring.data.redis.host");
            int expectedPort = context.getEnvironment().getRequiredProperty("spring.data.redis.port", Integer.class);
            require(expectedHost.equals(lettuce.getHostName()) && expectedPort == lettuce.getPort(),
                    "Actual Redis factory differs from the confirmed target");
            try (var connection = factory.getConnection()) {
                Object result = connection.execute("CLIENT", "INFO".getBytes(StandardCharsets.US_ASCII));
                String info = result instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(result);
                require(info.matches("(?s).*\\bdb=15(?:\\s|$).*"), "Actual Redis connection is not using DB15");
            }
        }
    }

    static void validateDatabaseIdentity(String database, String account) {
        require("flowdesk_test".equals(database) && account != null && account.startsWith("flowdesk_test_user@"),
                "Actual database/account is not the dedicated test target");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
