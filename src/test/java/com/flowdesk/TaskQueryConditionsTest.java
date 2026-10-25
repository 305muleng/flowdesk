package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.query.TaskQueryConditions;
import com.flowdesk.model.Task;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TaskQueryConditionsTest {

    @BeforeAll
    static void initializeTaskMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "task-query-conditions"), Task.class);
    }

    @Test
    void projectScopeExcludesDeletedAndTerminalTasksWithoutRestrictingAssignee() {
        var query = TaskQueryConditions.unfinishedInProject(7L);
        assertEquals(7L, parameterFor(query, "project_id"));
        assertFalse(query.getSqlSegment().contains("assignee_id"));
        assertUnfinishedAndNotDeleted(query);
    }

    @Test
    void assigneeScopeKeepsBothProjectAndMemberConstraints() {
        var query = TaskQueryConditions.unfinishedForAssignee(7L, 9L);
        assertEquals(7L, parameterFor(query, "project_id"));
        assertEquals(9L, parameterFor(query, "assignee_id"));
        assertUnfinishedAndNotDeleted(query);
    }

    @Test
    void modifyingOneQueryCannotLeakIntoAnotherProjectOrMember() {
        var first = TaskQueryConditions.unfinishedForAssignee(7L, 9L);
        var second = TaskQueryConditions.unfinishedForAssignee(8L, 10L);
        first.eq(Task::getTitle, "仅限第一个查询");
        assertNotSame(first, second);
        assertEquals(7L, parameterFor(first, "project_id"));
        assertEquals(9L, parameterFor(first, "assignee_id"));
        assertEquals(8L, parameterFor(second, "project_id"));
        assertEquals(10L, parameterFor(second, "assignee_id"));
        assertFalse(second.getSqlSegment().contains("title"));
    }

    private static Object parameterFor(LambdaQueryWrapper<Task> query, String column) {
        var matcher = Pattern.compile(column + " = #\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(query.getSqlSegment());
        assertTrue(matcher.find(), "缺少查询范围：" + column);
        return query.getParamNameValuePairs().get(matcher.group(1));
    }

    private static void assertUnfinishedAndNotDeleted(LambdaQueryWrapper<Task> query) {
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("deleted_at IS NULL"));
        var clause = Pattern.compile("status IN \\(([^)]+)\\)").matcher(sql);
        assertTrue(clause.find(), "缺少未完成状态范围");
        var parameters = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(clause.group(1));
        Set<Object> statuses = new HashSet<>();
        while (parameters.find()) statuses.add(query.getParamNameValuePairs().get(parameters.group(1)));
        assertEquals(Set.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.REVIEW), statuses);
    }
}
