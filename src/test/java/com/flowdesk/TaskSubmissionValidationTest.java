package com.flowdesk;

import com.flowdesk.controller.TaskController;
import com.flowdesk.controller.TaskQueryController;
import com.flowdesk.controller.TaskCommentController;
import com.flowdesk.dto.SubmitTaskDTO;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskService;
import com.flowdesk.service.TaskQueryService;
import com.flowdesk.service.TaskCommentService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskSubmissionValidationTest {
    TaskCommentService taskComments = org.mockito.Mockito.mock(TaskCommentService.class);
    TaskQueryService taskQueries = org.mockito.Mockito.mock(TaskQueryService.class);
    static Stream<Arguments> resultUrls() {
        return Stream.of(Arguments.of((String) null, true), Arguments.of("", true),
                Arguments.of(url(500), true), Arguments.of(url(501), false), Arguments.of(url(2000), false));
    }

    @ParameterizedTest
    @MethodSource("resultUrls")
    void resultUrlLengthIsCheckedBeforeCallingService(String resultUrl, boolean valid) throws Exception {
        TaskService service = mock(TaskService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new com.flowdesk.controller.TaskQueryController(taskQueries), new com.flowdesk.controller.TaskCommentController(taskComments), new TaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Map<String, Object> body = new HashMap<>();
        body.put("completionNote", "已完成实现");
        body.put("testNote", "完成回归检查");
        body.put("resultUrl", resultUrl);
        var request = post("/tasks/10/submit").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(body));
        if (valid) {
            when(service.submitTask(eq(10L), any(SubmitTaskDTO.class))).thenReturn(20L);
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(20));
            var dto = ArgumentCaptor.forClass(SubmitTaskDTO.class);
            verify(service).submitTask(eq(10L), dto.capture());
            assertEquals(resultUrl, dto.getValue().getResultUrl());
        } else {
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("成果链接长度不能超过500个字符"));
            verifyNoInteractions(service);
        }
    }

    private static String url(int length) {
        String prefix = "https://example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
