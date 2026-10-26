package com.flowdesk;

import com.flowdesk.controller.TaskController;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDeadlineWebTest {
    TaskService tasks;
    MockMvc mvc;
    @BeforeEach void setup(){
        tasks=mock(TaskService.class);
        mvc=MockMvcBuilders.standaloneSetup(new TaskController(tasks)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @ParameterizedTest
    @ValueSource(strings={"expectedDeadline","deadline","reason","BLANK","LONG","DATE"})
    void invalidAdjustmentsAreRejectedBeforeBusinessCode(String field) throws Exception {
        var body=new HashMap<String,Object>(Map.of("expectedDeadline","2099-01-01T12:00:00","deadline","2099-01-02T12:00:00","reason","排期变化"));
        if(field.equals("BLANK"))body.put("reason","  ");
        else if(field.equals("LONG"))body.put("reason","字".repeat(501));
        else if(field.equals("DATE"))body.put("deadline","invalid");
        else body.remove(field);
        mvc.perform(patch("/tasks/1/deadline").contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }
    @Test void validRequestUsesDedicatedAdjustmentOperation() throws Exception {
        mvc.perform(patch("/tasks/1/deadline").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedDeadline\":\"2099-01-01T12:00:00\",\"deadline\":\"2099-01-02T12:00:00\",\"reason\":\"排期变化\"}"))
            .andExpect(status().isOk());
        verify(tasks).adjustTaskDeadline(eq(1L),argThat(dto->dto.reason().equals("排期变化")));
    }
}
