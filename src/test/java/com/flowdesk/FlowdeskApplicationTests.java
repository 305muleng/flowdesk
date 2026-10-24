package com.flowdesk;

import com.flowdesk.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import com.flowdesk.snapshot.AcceptanceSnapshotCodec;
import com.flowdesk.service.ProjectPermissionService;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@IntegrationTest
class FlowdeskApplicationTests {

    @Autowired ApplicationContext context;

    @Test
    void contextLoadsWithoutSpecializedTestConfigurations() {
        assertTrue(context.getBeansOfType(AcceptanceSnapshotIntegrationTest.DatabaseConfig.class).isEmpty());
        assertTrue(context.getBeansOfType(ProjectManagerAuthorizationConcurrencyIntegrationTest.DatabaseConfig.class).isEmpty());
        assertTrue(context.getBeansOfType(DisabledMemberNotificationTest.Config.class).isEmpty());
        assertTrue(context.getBeansOfType(ProjectRequestClosureTransactionTest.Config.class).isEmpty());
        assertTrue(context.getBeansOfType(StubTransactionManager.class).isEmpty());
        assertEquals(AcceptanceSnapshotCodec.class, context.getBean(AcceptanceSnapshotCodec.class).getClass());
        assertEquals(ProjectPermissionService.class, context.getBean(ProjectPermissionService.class).getClass());
    }
}
