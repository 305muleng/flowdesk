package com.flowdesk;

import com.flowdesk.common.Result;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.controller.TaskController;
import com.flowdesk.controller.TaskQueryController;
import com.flowdesk.controller.TaskCommentController;
import com.flowdesk.enums.*;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskService;
import com.flowdesk.service.TaskQueryService;
import com.flowdesk.service.TaskCommentService;
import com.flowdesk.vo.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatusJsonCompatibilityTest {
    TaskCommentService taskComments = org.mockito.Mockito.mock(TaskCommentService.class);
    TaskQueryService taskQueries = org.mockito.Mockito.mock(TaskQueryService.class);

    private final JsonMapper mapper = JsonMapper.builder().build();

    record Codes(Class<? extends Enum<?>> type, String codes) {}

    static Stream<Codes> codes() {
        return Stream.of(
            new Codes(ProjectStatus.class, "PREPARING IN_PROGRESS PENDING_ACCEPTANCE COMPLETED ARCHIVED CANCELLED"),
            new Codes(TaskStatus.class, "TODO IN_PROGRESS REVIEW DONE CANCELLED"),
            new Codes(ProjectAcceptanceStatus.class, "PENDING APPROVED REJECTED"),
            new Codes(TaskSubmissionStatus.class, "PENDING APPROVED REJECTED"),
            new Codes(ProjectInvitationStatus.class, "PENDING ACCEPTED REJECTED CLOSED"),
            new Codes(TaskRequestStatus.class, "PENDING APPROVED REJECTED CANCELLED CLOSED"),
            new Codes(MemberLeaveRequestStatus.class, "PENDING APPROVED REJECTED CANCELLED CLOSED"),
            new Codes(ProjectManagerTransferStatus.class, "PENDING ACCEPTED REJECTED CANCELLED CLOSED"),
            new Codes(UserStatus.class, "ACTIVE DISABLED"),
            new Codes(ProjectMemberStatus.class, "ACTIVE INACTIVE")
        );
    }

    @ParameterizedTest
    @MethodSource("codes")
    void stableStringCodesRoundTripWithJackson3AndRejectUnknownValues(Codes expected) throws Exception {
        List<String> codes = Arrays.asList(expected.codes().split(" "));
        assertEquals(codes.size(), expected.type().getEnumConstants().length);
        for (int i = 0; i < codes.size(); i++) {
            Object status = expected.type().getEnumConstants()[i];
            String code = codes.get(i);
            assertEquals(code, expected.type().getMethod("getCode").invoke(status));
            assertEquals("\"" + code + "\"", mapper.writeValueAsString(status));
            assertSame(status, mapper.readValue("\"" + code + "\"", expected.type()));
            assertSame(status, expected.type().getMethod("fromCode", String.class).invoke(null, code));
        }
        assertEquals("null", mapper.writeValueAsString(null));
        assertNull(mapper.readValue("null", expected.type()));
        assertNull(
            expected
                .type()
                .getMethod("fromCode", String.class)
                .invoke(null, new Object[] { null })
        );
        for (String invalid : List.of("UNKNOWN", "", " pending ", "active")) {
            assertThrows(JacksonException.class, () -> mapper.readValue("\"" + invalid + "\"", expected.type()));
        }
    }

    record Boundary(Class<?> type, String property, String code) {}

    static Stream<Boundary> boundaries() {
        return Stream.of(
            new Boundary(ProjectVO.class, "status", "PENDING_ACCEPTANCE"),
            new Boundary(TaskVO.class, "status", "REVIEW"),
            new Boundary(ProjectAcceptanceVO.class, "reviewStatus", "APPROVED"),
            new Boundary(ProjectAcceptanceDetailVO.class, "reviewStatus", "REJECTED"),
            new Boundary(TaskSubmissionVO.class, "reviewStatus", "PENDING"),
            new Boundary(InvitationVO.class, "status", "CLOSED"),
            new Boundary(TaskRequestVO.class, "status", "CLOSED"),
            new Boundary(MemberLeaveRequestVO.class, "status", "EXPIRED"),
            new Boundary(ProjectManagerTransferVO.class, "status", "CLOSED"),
            new Boundary(AdminUserVO.class, "status", "DISABLED"),
            new Boundary(UserProfileVO.class, "status", "ACTIVE"),
            new Boundary(ProjectMemberVO.class, "userStatus", "DISABLED"),
            new Boundary(MemberCandidateVO.class, "userStatus", "ACTIVE")
        );
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void resultVoPropertyNamesStringCodesAndNullsRemainCompatible(Boundary boundary) throws Exception {
        Object vo = boundary.type().getConstructor().newInstance();
        var setter = boundary
            .type()
            .getMethod(
                "set" + Character.toUpperCase(boundary.property().charAt(0)) + boundary.property().substring(1),
                String.class
            );
        setter.invoke(vo, boundary.code());
        var json = mapper.readTree(mapper.writeValueAsString(Result.success(vo)));
        assertEquals(200, json.get("code").asInt());
        assertTrue(json.get("data").get(boundary.property()).isString());
        assertEquals(boundary.code(), json.get("data").get(boundary.property()).asString());
        setter.invoke(vo, new Object[] { null });
        var nullJson = mapper.readTree(mapper.writeValueAsString(Result.success(vo)));
        assertTrue(nullJson.get("data").has(boundary.property()));
        assertTrue(nullJson.get("data").get(boundary.property()).isNull());
    }

    @Test
    void sameSpellingDoesNotMergeDifferentBusinesses() {
        assertNotEquals(ProjectStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS);
        assertNotEquals(UserStatus.ACTIVE, ProjectMemberStatus.ACTIVE);
        assertNotEquals(ProjectAcceptanceStatus.PENDING, TaskSubmissionStatus.PENDING);
        assertNotEquals(ProjectInvitationStatus.PENDING, TaskRequestStatus.PENDING);
    }

    @Test
    void httpTaskOutputStaysStringAndInvalidFilterStillReturns400FromRealValidation() throws Exception {
        // 只让非法筛选进入真实校验；没有初始化 Mapper，也不会加载应用或外部环境。
        TaskService service = mock(TaskService.class);
        TaskQueryService taskQueries = mock(TaskQueryService.class, CALLS_REAL_METHODS);
        TaskVO task = new TaskVO();
        task.setStatus(TaskStatus.REVIEW.getCode());
        doReturn(task).when(taskQueries).getTaskDetail(7L);
        var mvc = MockMvcBuilders.standaloneSetup(new com.flowdesk.controller.TaskQueryController(taskQueries), new com.flowdesk.controller.TaskCommentController(taskComments), new TaskController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        try {
            UserContext.set(new CurrentUser(2L, "USER"));
            mvc.perform(get("/tasks/7")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVIEW"));
            mvc.perform(get("/tasks/my").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("任务状态筛选条件不正确"));
        } finally {
            UserContext.remove();
        }
    }
}
