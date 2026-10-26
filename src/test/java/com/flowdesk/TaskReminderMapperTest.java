package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.flowdesk.mapper.TaskReminderMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class TaskReminderMapperTest {
    @Test
    void reminderStatementsBindNullableAssigneesAndRecordParametersWithoutAConnection() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TaskReminderMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("taskId", 10L); parameters.put("projectId", 4L); parameters.put("id", 20L);
        parameters.put("deadline", LocalDateTime.now()); parameters.put("assigneeId", null);
        parameters.put("revision", 1L); parameters.put("kind", "DUE_SOON");
        parameters.put("now", LocalDateTime.now()); parameters.put("next", LocalDateTime.now().plusMinutes(1));
        parameters.put("windowEnd", LocalDateTime.now().plusHours(24)); parameters.put("afterId", 0L);
        parameters.put("limit", 100); parameters.put("state", "DONE"); parameters.put("error", "失败"); parameters.put("userId", 2L);
        for (var method : TaskReminderMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(TaskReminderMapper.class.getName() + "." + method.getName());
            var sql = statement.getBoundSql(parameters);
            assertFalse(sql.getSql().isBlank());
            for (var binding : sql.getParameterMappings()) assertTrue(parameters.containsKey(binding.getProperty()), binding.getProperty());
        }
        var reopen = configuration.getMappedStatement(TaskReminderMapper.class.getName() + ".reopenCancelled")
            .getBoundSql(parameters).getSql();
        assertTrue(reopen.contains("AND state = 'CANCELLED'"));
        // 此检查仅证明 MyBatis 可以构建语句；MySQL 语法、执行计划和真实锁行为留待统一验收。
    }
}
