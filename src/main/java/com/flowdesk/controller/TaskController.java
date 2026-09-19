package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.*;
import com.flowdesk.service.TaskService;
import com.flowdesk.vo.TaskCommentVO;
import com.flowdesk.vo.TaskSubmissionVO;
import com.flowdesk.vo.TaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "任务管理",
        description = "任务详情、负责人分配、执行、提交、审核、取消以及评论相关接口"
)
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/my")
    public Result<List<TaskVO>> getMyTasks() {
        return Result.success(taskService.getMyTasks());
    }

    @Operation(
            summary = "开始任务",
            description = "任务负责人开始执行任务，使任务从 TODO 进入 IN_PROGRESS"
    )
    @PostMapping("/{taskId}/start")
    public Result<Void> startTask(@PathVariable Long taskId) {
        taskService.startTask(taskId);
        return Result.success("任务已开始", null);
    }

    @Operation(
            summary = "提交任务",
            description = "任务负责人提交完成说明、结果链接和测试说明，使任务进入 REVIEW"
    )
    @PostMapping("/{taskId}/submit")
    public Result<Long> submitTask(@PathVariable Long taskId, @Valid @RequestBody SubmitTaskDTO dto) {
        Long submissionId = taskService.submitTask(taskId, dto);
        return Result.success("任务已提交审核", submissionId);
    }

    @Operation(
            summary = "审核提交的任务",
            description = "项目负责人审核任务提交；通过后任务进入 DONE，驳回后返回 IN_PROGRESS"
    )
    @PostMapping("/{taskId}/review")
    public Result<Void> reviewTask(@PathVariable Long taskId, @Valid @RequestBody ReviewTaskDTO dto) {
        taskService.reviewTask(taskId, dto);
        return Result.success("任务审核完成", null);
    }

    @Operation(
            summary = "查看任务提交记录",
            description = "查看任务历次提交及审核结果"
    )
    @GetMapping("/{taskId}/submissions")
    public Result<List<TaskSubmissionVO>> getTaskSubmissions(@PathVariable Long taskId) {
        List<TaskSubmissionVO> submissions = taskService.getTaskSubmissions(taskId);
        return Result.success(submissions);
    }

    @Operation(
            summary = "分配任务负责人",
            description = "项目负责人为 TODO 状态的任务分配或重新分配负责人"
    )
    @PatchMapping("/{taskId}/assignee")
    public Result<Void> assignTask(@PathVariable Long taskId, @Valid @RequestBody AssignTaskDTO dto) {
        taskService.assignTask(taskId, dto);
        return Result.success("任务负责人分配成功", null);
    }

    @Operation(
            summary = "查看任务详情",
            description = "项目成员查看指定任务的详细信息"
    )
    @GetMapping("/{taskId}")
    public Result<TaskVO> getTaskDetail(@PathVariable Long taskId) {
        TaskVO taskVO = taskService.getTaskDetail(taskId);
        return Result.success(taskVO);
    }

    @Operation(
            summary = "新增任务评论",
            description = "项目成员对任务发表评论，也可以通过 parentId 回复已有评论"
    )
    @PostMapping("/{taskId}/comments")
    public Result<Long> addTaskComment(@PathVariable Long taskId, @Valid @RequestBody AddTaskCommentDTO dto) {
        Long commentId = taskService.addTaskComment(taskId, dto);
        return Result.success("评论发表成功", commentId);
    }

    @Operation(
            summary = "查看任务评论",
            description = "项目成员查看指定任务的历史评论"
    )
    @GetMapping("/{taskId}/comments")
    public Result<List<TaskCommentVO>> getTaskComments(@PathVariable Long taskId) {
        List<TaskCommentVO> comments = taskService.getTaskComments(taskId);
        return Result.success(comments);
    }

    @Operation(
            summary = "取消任务",
            description = "项目负责人取消 TODO 或 IN_PROGRESS 状态的任务，并填写取消原因"
    )
    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable Long taskId, @Valid @RequestBody CancelTaskDTO dto) {
        taskService.cancelTask(taskId, dto);
        return Result.success("任务取消成功", null);
    }
}
