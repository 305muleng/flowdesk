package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.*;
import com.flowdesk.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;


@Tag(name = "任务管理", description = "任务详情、负责人分配、执行、提交、审核、取消以及评论相关接口")
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "编辑任务资料", description = "项目负责人编辑准备中或进行中项目的 TODO 任务，不修改负责人或状态")
    @PatchMapping("/{taskId}")
    public Result<Void> updateTaskDetails(@PathVariable Long taskId, @Valid @RequestBody UpdateTaskDTO dto) {
        taskService.updateTaskDetails(taskId, dto);
        return Result.success("任务资料已更新", null);
    }

    @Operation(summary = "开始任务", description = "任务负责人开始执行任务，使任务从 TODO 进入 IN_PROGRESS")
    @PostMapping("/{taskId}/start")
    public Result<Void> startTask(@PathVariable Long taskId) {
        taskService.startTask(taskId);
        return Result.success("任务已开始", null);
    }

    @Operation(summary = "提交任务", description = "任务负责人提交完成说明、结果链接和测试说明，使任务进入 REVIEW")
    @PostMapping("/{taskId}/submit")
    public Result<Long> submitTask(@PathVariable Long taskId, @Valid @RequestBody SubmitTaskDTO dto) {
        Long submissionId = taskService.submitTask(taskId, dto);
        return Result.success("任务已提交审核", submissionId);
    }

    @Operation(summary = "审核提交的任务", description = "项目负责人审核任务提交；通过后任务进入 DONE，驳回后返回 IN_PROGRESS")
    @PostMapping("/{taskId}/review")
    public Result<Void> reviewTask(@PathVariable Long taskId, @Valid @RequestBody ReviewTaskDTO dto) {
        taskService.reviewTask(taskId, dto);
        return Result.success("任务审核完成", null);
    }

    @Operation(summary = "分配任务负责人", description = "项目负责人为 TODO 状态的任务分配或重新分配负责人")
    @PatchMapping("/{taskId}/assignee")
    public Result<Void> assignTask(@PathVariable Long taskId, @Valid @RequestBody AssignTaskDTO dto) {
        taskService.assignTask(taskId, dto);
        return Result.success("任务负责人分配成功", null);
    }

    @Operation(summary = "交接禁用成员任务", description = "项目负责人将禁用账号的进行中任务交接给有效成员，任务回到 TODO")
    @PostMapping("/{taskId}/handover")
    public Result<Void> handoverTask(@PathVariable Long taskId, @Valid @RequestBody HandoverTaskDTO dto) {
        taskService.handoverDisabledAssignee(taskId, dto);
        return Result.success("任务已交接，等待新任务负责人开始", null);
    }

    @Operation(summary = "取消任务", description = "项目负责人取消 TODO 或 IN_PROGRESS 状态的任务，并填写取消原因")
    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable Long taskId, @Valid @RequestBody CancelTaskDTO dto) {
        taskService.cancelTask(taskId, dto);
        return Result.success("任务取消成功", null);
    }
}
