package com.flowdesk;

import com.flowdesk.mapper.TaskMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

// 注解 SQL 声明检查，不执行 SQL，也不证明数据库实际计数或执行计划。
class ListPaginationSqlContractTest {

    String sql(String name) {
        Method method = Arrays.stream(TaskMapper.class.getMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow();
        return String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").trim();
    }

    @Test
    void projectScopeDeletionAndAllFiltersAreIntersectionsWithStableOrdering() {
        String sql = sql("selectProjectTasks");
        assertTrue(sql.contains("WHERE t.project_id = #{projectId} AND t.deleted_at IS NULL"));
        assertTrue(sql.contains("#{scope} = 'MINE' AND t.assignee_id = #{currentUserId}"));
        assertTrue(sql.contains("AND ( #{status} = 'ALL' OR t.status = #{status} )"));
        assertTrue(sql.contains("AND (#{assigneeId} IS NULL OR t.assignee_id = #{assigneeId})"));
        assertTrue(sql.contains("AND (#{unfinishedOnly} = FALSE OR t.status IN ('TODO', 'IN_PROGRESS', 'REVIEW'))"));
        assertTrue(sql.endsWith("ORDER BY t.created_at DESC, t.id DESC"));
        assertFalse(sql.contains("${"));
    }

    @Test
    void myListAndSummaryShareExactlyTheSamePermissionScopeAndSummaryIsUnfiltered() {
        String list = sql("selectMyTasks"),
            summary = sql("selectMyTaskSummary");
        String scope = TaskMapper.MY_TASK_SCOPE.replaceAll("\\s+", " ").trim();
        assertTrue(list.contains(scope));
        assertTrue(summary.endsWith(scope));
        assertTrue(scope.contains("p.deleted_at IS NULL"));
        assertTrue(scope.contains("pm.user_id = #{userId} AND pm.status = 'ACTIVE'"));
        assertTrue(scope.contains("current_account.status = 'ACTIVE' AND current_account.system_role = 'USER'"));
        assertTrue(scope.endsWith("WHERE t.assignee_id = #{userId} AND t.deleted_at IS NULL"));
        assertTrue(list.contains("AND (#{status} = 'ALL' OR t.status = #{status})"));
        assertTrue(list.contains("LOCATE(CAST(#{keyword} AS BINARY), CAST(LOWER(t.title) AS BINARY)) > 0"));
        assertTrue(list.endsWith("t.updated_at DESC, t.id DESC"));
        assertFalse(list.contains("${"));
        assertFalse(summary.contains("#{status}"));
        assertFalse(summary.contains("#{keyword}"));
        assertFalse(summary.contains("LIMIT"));
        assertTrue(summary.contains("t.deadline >= #{now} AND t.deadline < #{dueBefore}"));
        assertTrue(summary.contains("SUM(t.status NOT IN ('DONE', 'CANCELLED'))"));
        assertTrue(summary.contains("SUM(t.status != 'CANCELLED')"));
        for (String alias : new String[] {
            "totalTaskCount",
            "todoTaskCount",
            "inProgressTaskCount",
            "reviewTaskCount",
            "doneTaskCount",
            "unfinishedTaskCount",
            "nonCancelledTaskCount",
            "dueSoonTaskCount"
        }) {
            assertTrue(summary.contains(" AS " + alias));
        }
    }
}
