package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.CreateTaskRequestDTO;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.service.TaskRequestService;
import com.flowdesk.vo.TaskRequestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "任务申请",
        description = "开发人员提交任务申请、查看申请以及项目负责人审批任务申请相关接口"
)
@RestController
@RequestMapping("/projects/{projectId}/task-requests")
public class TaskRequestController {

    private final TaskRequestService taskRequestService;

    public TaskRequestController(TaskRequestService taskRequestService) {
        this.taskRequestService = taskRequestService;
    }

    @Operation(
            summary = "创建任务申请",
            description = "项目成员向项目负责人提交新的任务申请"
    )
    @PostMapping
    public Result<Long> createTaskRequest(@PathVariable Long projectId, @Valid @RequestBody CreateTaskRequestDTO dto) {
        Long requestId = taskRequestService.createTaskRequest(projectId, dto);
        return Result.success("任务申请提交成功", requestId);
    }

    @Operation(
            summary = "查看项目任务申请",
            description = "项目负责人查看指定项目中的任务申请，可按状态筛选"
    )
    @GetMapping
    public Result<List<TaskRequestVO>> getProjectTaskRequests(@PathVariable Long projectId, @RequestParam(defaultValue = "ALL") String status) {
        return Result.success(taskRequestService.getProjectTaskRequests(projectId, status));
    }

    @Operation(
            summary = "审批任务申请",
            description = "项目负责人批准或驳回待处理任务申请；批准后会创建正式任务"
    )
    @PostMapping("/{requestId}/review")
    public Result<Long> reviewTaskRequest(@PathVariable Long projectId, @PathVariable Long requestId, @Valid @RequestBody ReviewTaskRequestDTO dto) {
        Long taskId = taskRequestService.reviewTaskRequest(projectId, requestId, dto);
        return Result.success("任务申请审批完成", taskId);
    }

    @Operation(
            summary = "取消任务申请",
            description = "任务申请人取消自己仍处于 PENDING 状态的任务申请"
    )
    @PostMapping("/{requestId}/cancel")
    public Result<Void> cancelTaskRequest(@PathVariable Long projectId, @PathVariable Long requestId) {
        taskRequestService.cancelTaskRequest(projectId, requestId);
        return Result.success("任务申请已撤回", null);
    }
}