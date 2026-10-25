package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.controller.TaskRequestController;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.TaskRequestStatus;
import com.flowdesk.enums.UserStatus;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.User;
import com.flowdesk.service.TaskRequestService;
import com.flowdesk.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
@Transactional
class TaskRequestCreationIntegrationTest {
    @Autowired UserMapper users;
    @Autowired ProjectMapper projects;
    @Autowired ProjectMemberMapper members;
    @Autowired TaskRequestMapper requests;
    @Autowired TaskMapper tasks;
    @Autowired TaskRequestService service;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    long managerId;
    long developerId;
    long projectId;

    @BeforeEach
    void prepareFixture() {
        managerId = createUser();
        developerId = createUser();
        var project = new Project();
        project.setCreatorId(managerId);
        project.setName("任务申请必填字段回归");
        project.setDescription("验证必填内容与真实数据库一致");
        project.setGoal("创建申请与批准为正式任务均可正常完成");
        project.setStatus(ProjectStatus.IN_PROGRESS);
        project.setStartTime(LocalDateTime.now());
        project.setExpectedEndTime(LocalDateTime.now().plusDays(30));
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projects.insert(project);
        projectId = project.getId();
        createMember(managerId, "PROJECT_MANAGER");
        createMember(developerId, "DEVELOPER");
        // 使用真实事务 Service 和 MySQL；这里只模拟请求传输，身份由测试明确设置。
        mvc = MockMvcBuilders.standaloneSetup(new TaskRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new CurrentUser(developerId, "USER"));
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
        // 测试事务回滚本用例的全部记录，避免在测试库留下申请、通知或任务。
    }

    static Stream<Arguments> validFields() {
        return Stream.of(
                Arguments.of(",\"description\":\"描述\",\"goal\":\"目标\"", "描述", "目标"),
                Arguments.of(",\"description\":\" 保留描述 \",\"goal\":\"保留目标\"", " 保留描述 ", "保留目标"));
    }

    @ParameterizedTest
    @MethodSource("validFields")
    void requiredFieldsPersistAndRemainValidWhenApproved(String fields, String description, String goal) throws Exception {
        String body = "{\"title\":\"必填字段验证\"" + fields + "}";
        var response = mvc.perform(post("/projects/{projectId}/task-requests", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long requestId = json.readTree(response.getResponse().getContentAsString()).get("data").asLong();
        var request = requests.selectById(requestId);
        assertEquals(description, request.getDescription());
        assertEquals(goal, request.getGoal());

        UserContext.set(new CurrentUser(managerId, "USER"));
        var approval = new ReviewTaskRequestDTO();
        approval.setAction("APPROVE");
        approval.setAssigneeId(developerId);
        approval.setDeadline(LocalDateTime.now().plusDays(2));
        long taskId = service.reviewTaskRequest(projectId, requestId, approval);
        var task = tasks.selectById(taskId);
        assertNotNull(task);
        assertEquals(description, task.getDescription());
        assertEquals(goal, task.getGoal());
        assertEquals(TaskRequestStatus.APPROVED, requests.selectById(requestId).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"description", "goal"})
    void omittedRequiredFieldReturns400WithoutRequestOrSideEffects(String missingField) throws Exception {
        String presentField = missingField.equals("description") ? "goal" : "description";
        String body = "{\"title\":\"缺少必填内容\",\"" + presentField + "\":\"已填写\"}";
        mvc.perform(post("/projects/{projectId}/task-requests", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        for (String table : java.util.List.of("task_request", "notification", "operation_log")) {
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `" + table + "` WHERE project_id = ?", Long.class, projectId));
        }
    }

    private long createUser() {
        var user = new User();
        user.setUsername("req_req_" + UUID.randomUUID());
        user.setPasswordHash("test-only-hash");
        user.setRealName("必填字段测试用户");
        user.setSystemRole("USER");
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        users.insert(user);
        return user.getId();
    }

    private void createMember(long userId, String role) {
        var member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(ProjectMemberStatus.ACTIVE);
        member.setJoinedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        members.insert(member);
    }
}
