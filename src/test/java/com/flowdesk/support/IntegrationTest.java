package com.flowdesk.support;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import java.lang.annotation.*;

/** 需要显式选择的外部环境测试，不纳入默认快速测试集。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("integration")
@ContextConfiguration(initializers = IntegrationEnvironmentGuard.class)
@TestExecutionListeners(listeners = IntegrationEnvironmentGuard.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IntegrationTest {}
