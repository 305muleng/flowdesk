package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.vo.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 仅验证 SQL 声明和映射契约，不执行 SQL，不代表真实数据库行更新已验证。
class RequestClosureQueryContractTest {

    @Test
    void closureSqlOnlyUpdatesPendingCurrentProjectAndDedicatedClosureColumns() throws Exception {
        for (Class<?> mapper : List.of(
            ProjectInvitationMapper.class,
            MemberLeaveRequestMapper.class,
            ProjectManagerTransferMapper.class,
            TaskRequestMapper.class
        )) {
            String sql = String.join(
                " ",
                mapper
                    .getMethod("closePendingForProject", Long.class, LocalDateTime.class, String.class)
                    .getAnnotation(Update.class)
                    .value()
            )
                .replaceAll("\\s+", " ")
                .trim();
            assertTrue(sql.contains("WHERE project_id = #{projectId} AND status = 'PENDING'"), mapper.getSimpleName());
            assertEquals(
                "status = 'CLOSED', closed_at = #{closedAt}, close_reason = #{closeReason}",
                sql.substring(sql.indexOf("SET ") + 4, sql.indexOf(" WHERE"))
            );
            if (mapper == TaskRequestMapper.class) assertFalse(sql.contains("expires_at"));
            else assertTrue(sql.endsWith("AND expires_at > #{closedAt}"));
            assertFalse(sql.contains(" OR "));
            assertFalse(sql.contains("status IN"));
            assertFalse(sql.contains("DELETE"));
        }
    }

    @Test
    void allExplicitRequestSelectsExposeBothClosureFields() {
        for (Class<?> mapper : List.of(
            ProjectInvitationMapper.class,
            MemberLeaveRequestMapper.class,
            ProjectManagerTransferMapper.class,
            TaskRequestMapper.class
        )) {
            for (var method : mapper.getDeclaredMethods()) {
                Select select = method.getAnnotation(Select.class);
                if (select == null) continue;
                String sql = String.join(" ", select.value());
                assertTrue(sql.contains("closed_at AS closedAt"), method.toString());
                assertTrue(sql.contains("close_reason AS closeReason"), method.toString());
            }
        }
    }

    @Test
    void entityColumnMappingAndAllPayloadsKeepNullableClosureFields() throws Exception {
        for (Class<?> type : List.of(
            ProjectInvitation.class,
            MemberLeaveRequest.class,
            ProjectManagerTransfer.class,
            TaskRequest.class,
            InvitationVO.class,
            MemberLeaveRequestVO.class,
            ProjectManagerTransferVO.class,
            TaskRequestVO.class
        )) {
            Object record = type.getConstructor().newInstance();
            assertNull(type.getMethod("getClosedAt").invoke(record));
            assertNull(type.getMethod("getCloseReason").invoke(record));
            LocalDateTime time = LocalDateTime.of(2026, 10, 18, 12, 0);
            type.getMethod("setClosedAt", LocalDateTime.class).invoke(record, time);
            type.getMethod("setCloseReason", String.class).invoke(record, "PROJECT_CANCELLED");
            assertEquals(time, type.getMethod("getClosedAt").invoke(record));
            assertEquals("PROJECT_CANCELLED", type.getMethod("getCloseReason").invoke(record));
            if (type.getPackageName().endsWith(".model")) {
                var info = TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "closure-contract"),
                    type
                );
                assertTrue(
                    info
                        .getFieldList()
                        .stream()
                        .anyMatch(f -> f.getProperty().equals("closedAt") && f.getColumn().equals("closed_at"))
                );
                assertTrue(
                    info
                        .getFieldList()
                        .stream()
                        .anyMatch(f -> f.getProperty().equals("closeReason") && f.getColumn().equals("close_reason"))
                );
            }
        }
    }

    @Test
    void pendingOnlyCleanupAndExpiryFiltersCannotTreatClosedAsPending() throws Exception {
        for (Class<?> mapper : List.of(
            ProjectInvitationMapper.class,
            MemberLeaveRequestMapper.class,
            ProjectManagerTransferMapper.class,
            TaskRequestMapper.class
        )) {
            for (var method : mapper.getDeclaredMethods()) {
                Update update = method.getAnnotation(Update.class);
                if (update == null || method.getName().equals("closePendingForProject")) continue;
                String sql = String.join(" ", update.value());
                assertTrue(sql.contains("status = 'PENDING'"), method.toString());
                assertFalse(sql.contains("closed_at") || sql.contains("close_reason"), method.toString());
            }
        }
        String sql = String.join(
            " ",
            ProjectInvitationMapper.class
                .getMethod("selectMyInvitations", Long.class, String.class, LocalDateTime.class)
                .getAnnotation(Select.class)
                .value()
        );
        assertTrue(sql.contains("#{filter} = 'CLOSED' AND pi.status = 'CLOSED'"));
        assertTrue(sql.contains("pi.status = 'PENDING'"));
        assertTrue(sql.contains("pi.expires_at <= #{now}"));
    }
}
