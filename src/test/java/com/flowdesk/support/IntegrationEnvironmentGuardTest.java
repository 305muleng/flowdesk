package com.flowdesk.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationEnvironmentGuardTest {
    Map<String, String> settings() {
        return new HashMap<>(Map.of(
                "flowdesk.test.redis-confirmed", "true",
                "spring.datasource.url", "jdbc:mysql://localhost:3306/flowdesk_test?characterEncoding=UTF-8",
                "spring.datasource.username", "flowdesk_test_user",
                "spring.datasource.password", "synthetic-test-value",
                "spring.data.redis.host", "127.0.0.1",
                "spring.data.redis.port", "6379",
                "spring.data.redis.database", "15"));
    }

    @Test void dedicatedConfigurationIsAccepted() {
        assertDoesNotThrow(() -> IntegrationEnvironmentGuard.validateSettings(settings()::get));
    }

    @ParameterizedTest
    @CsvSource({
            "flowdesk.test.redis-confirmed,false",
            "spring.datasource.url,jdbc:mysql://localhost:3306/flowdesk",
            "spring.datasource.url,jdbc:mysql://example.com:3306/flowdesk_test",
            "spring.datasource.username,root",
            "spring.data.redis.host,example.com",
            "spring.data.redis.database,0",
            "spring.data.redis.port,0",
            "spring.data.redis.port,not-a-port"
    })
    void unsafeTargetsFailBeforeContextRefresh(String key, String value) {
        var config = settings(); config.put(key, value);
        assertThrows(IllegalStateException.class, () -> IntegrationEnvironmentGuard.validateSettings(config::get));
    }

    @Test void missingConfirmationAndCredentialsCannotSilentlyPass() {
        var config = settings(); config.remove("flowdesk.test.redis-confirmed");
        assertThrows(IllegalStateException.class, () -> IntegrationEnvironmentGuard.validateSettings(config::get));
        var noPassword = settings(); noPassword.put("spring.datasource.password", " ");
        assertThrows(IllegalStateException.class, () -> IntegrationEnvironmentGuard.validateSettings(noPassword::get));
    }

    @Test void actualConnectionIdentityMustMatchEvenWhenConfigurationLooksSafe() {
        assertDoesNotThrow(() -> IntegrationEnvironmentGuard.validateDatabaseIdentity("flowdesk_test", "flowdesk_test_user@localhost"));
        assertThrows(IllegalStateException.class, () -> IntegrationEnvironmentGuard.validateDatabaseIdentity("flowdesk", "flowdesk_test_user@localhost"));
        assertThrows(IllegalStateException.class, () -> IntegrationEnvironmentGuard.validateDatabaseIdentity("flowdesk_test", "root@localhost"));
    }
}
