package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.flowdesk.controller.ProjectController;
import com.flowdesk.controller.TaskController;
import com.flowdesk.controller.TaskQueryController;
import com.flowdesk.controller.TaskCommentController;
import com.flowdesk.dto.UpdateProjectDTO;
import com.flowdesk.dto.UpdateTaskDTO;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.Task;
import com.flowdesk.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DetailsEditingWebTest {
    TaskCommentService taskComments = org.mockito.Mockito.mock(TaskCommentService.class);
    TaskQueryService taskQueries = org.mockito.Mockito.mock(TaskQueryService.class);
    ProjectService projects;
    TaskService tasks;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        projects = mock(ProjectService.class);
        tasks = mock(TaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(new com.flowdesk.controller.TaskQueryController(taskQueries), new com.flowdesk.controller.TaskCommentController(taskComments), new TaskController(tasks),
                new ProjectController(projects, mock(ProjectInvitationService.class), tasks, mock(ProjectTaskSummaryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.concat(
            Stream.of("name", "title", "description", "goal", "deadline").flatMap(field ->
                Stream.of("MISSING", "NULL", "BLANK").map(value -> Arguments.of(field, value))),
            Stream.of(Arguments.of("name", "LONG"), Arguments.of("title", "LONG")));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void editingEntryRejectsMissingNullBlankAndOverlongRequiredFields(String field, String value) throws Exception {
        boolean project = field.equals("name");
        Map<String, Object> body = new HashMap<>();
        if (project) body.put("name", "项目");
        else {
            body.put("title", "任务"); body.put("description", "描述"); body.put("goal", "目标");
            body.put("deadline", "2099-01-01T12:00:00");
        }
        if (value.equals("MISSING")) body.remove(field);
        else body.put(field, value.equals("NULL") ? null : value.equals("BLANK") ? "   " : "a".repeat(project ? 101 : 201));
        mvc.perform(patch(project ? "/projects/4" : "/tasks/10").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(projects, tasks);
    }

    @Test
    void projectEditAllowsOptionalFieldsToBeCleared() throws Exception {
        mvc.perform(patch("/projects/4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目\",\"description\":null,\"goal\":null,\"expectedEndTime\":null}"))
                .andExpect(status().isOk());
        var dto = ArgumentCaptor.forClass(UpdateProjectDTO.class);
        verify(projects).updateProjectDetails(eq(4L), dto.capture());
        assertNull(dto.getValue().getDescription());
        assertNull(dto.getValue().getGoal());
        assertNull(dto.getValue().getExpectedEndTime());
    }

    @Test
    void taskEditBindsOnlyItsEditableFields() throws Exception {
        mvc.perform(patch("/tasks/10").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"任务\",\"description\":\"描述\",\"goal\":\"目标\",\"priority\":\"HIGH\",\"deadline\":\"2099-01-01T12:00:00\"}"))
                .andExpect(status().isOk());
        var dto = ArgumentCaptor.forClass(UpdateTaskDTO.class);
        verify(tasks).updateTaskDetails(eq(10L), dto.capture());
        assertEquals("HIGH", dto.getValue().getPriority());
        assertEquals("目标", dto.getValue().getGoal());
    }

    @Test
    void updateSqlUsesOnlyApprovedColumnsAndGuardsEditableStates() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(ProjectMapper.class);
        configuration.addMapper(TaskMapper.class);
        String projectSql = configuration.getMappedStatement(ProjectMapper.class.getName() + ".updateDetailsIfEditable")
                .getBoundSql(Map.of("project", new Project())).getSql().replaceAll("\\s+", " ");
        String taskSql = configuration.getMappedStatement(TaskMapper.class.getName() + ".updateDetailsIfTodo")
                .getBoundSql(Map.of("task", new Task())).getSql().replaceAll("\\s+", " ");
        assertTrue(projectSql.contains("status IN ('PREPARING', 'IN_PROGRESS') AND deleted_at IS NULL"));
        assertTrue(taskSql.contains("project_id = ? AND status = 'TODO' AND deleted_at IS NULL"));
        for (String sql : new String[]{projectSql, taskSql}) {
            String set = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));
            assertFalse(set.contains("status"));
            assertFalse(set.contains("assignee_id"));
            assertFalse(set.contains("creator_id"));
            assertFalse(set.contains("repository_url"));
            assertFalse(sql.contains("project_acceptance"));
            assertFalse(sql.contains("snapshot"));
        }
    }
}
