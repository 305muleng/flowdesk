package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.service.TaskRequestService;
import com.flowdesk.vo.TaskRequestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "我的任务申请", description = "当前用户查看自己提交的任务申请")
@RestController
@RequestMapping("/task-requests")
public class MyTaskRequestController {

    private final TaskRequestService taskRequestService;

    public MyTaskRequestController(TaskRequestService taskRequestService) {
        this.taskRequestService = taskRequestService;
    }

    @Operation(summary = "查看我的任务申请")
    @GetMapping("/my")
    public Result<List<TaskRequestVO>> getMyTaskRequests(
            @RequestParam(defaultValue = "ALL") String status) {
        return Result.success(taskRequestService.getMyTaskRequests(status));
    }
}
