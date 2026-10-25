
package com.flowdesk;

import com.flowdesk.dto.CreateTaskDTO;
import com.flowdesk.controller.ProjectController;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.ProjectService;
import com.flowdesk.service.ProjectInvitationService;
import com.flowdesk.service.ProjectTaskSummaryService;
import com.flowdesk.service.TaskService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateTaskDTOValidationTest {

    @Test
    void missingDescriptionAndGoalShouldFailValidation() {
        CreateTaskDTO dto = new CreateTaskDTO();

        dto.setTitle("测试任务");
        dto.setDeadline(LocalDateTime.now().plusDays(1));

        // description 和 goal 故意不赋值

        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {

            Validator validator = factory.getValidator();

            Set<String> invalidFields =
                    validator.validate(dto).stream()
                            .map(v -> v.getPropertyPath().toString())
                            .collect(Collectors.toSet());

            assertEquals(
                    Set.of("description", "goal"),
                    invalidFields
            );
        }
    }
    static Stream<Arguments> invalidRequiredFields() {
        return Stream.of("description", "goal").flatMap(field ->
                Stream.of("", ",\"" + field + "\":null", ",\"" + field + "\":\"\"",
                                ",\"" + field + "\":\" \\t\\n\"")
                        .map(fragment -> Arguments.of(
                                field.equals("description") ? ",\"goal\":\"明确目标\"" + fragment
                                        : ",\"description\":\"明确描述\"" + fragment,
                                field.equals("description") ? "任务描述不能为空" : "任务目标不能为空")));
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredFields")
    void invalidTaskContentReturns400BeforeCallingService(String fields, String message) throws Exception {
        TaskService service = mock(TaskService.class);
        mvc(service).perform(post("/projects/1/tasks").contentType(MediaType.APPLICATION_JSON).content(body(fields)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(message));
        verifyNoInteractions(service);
    }

    @Test
    void validTaskPreservesDescriptionAndGoal() throws Exception {
        TaskService service = mock(TaskService.class);
        when(service.createTask(eq(1L), any(CreateTaskDTO.class))).thenReturn(12L);
        mvc(service).perform(post("/projects/1/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(body(",\"description\":\"明确描述\",\"goal\":\"明确目标\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(12));
        var captor = ArgumentCaptor.forClass(CreateTaskDTO.class);
        verify(service).createTask(eq(1L), captor.capture());
        assertEquals("明确描述", captor.getValue().getDescription());
        assertEquals("明确目标", captor.getValue().getGoal());
    }

    private MockMvc mvc(TaskService service) {
        return MockMvcBuilders.standaloneSetup(new ProjectController(
                        mock(ProjectService.class), mock(ProjectInvitationService.class), service,
                        mock(ProjectTaskSummaryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String body(String fields) {
        return "{\"title\":\"测试任务\",\"deadline\":\"" + LocalDateTime.now().plusDays(1) + "\"" + fields + "}";
    }

}
