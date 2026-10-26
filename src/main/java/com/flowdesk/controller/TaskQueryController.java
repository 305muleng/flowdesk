package com.flowdesk.controller;

import com.flowdesk.common.*;
import com.flowdesk.dto.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "任务管理")
@RestController
public class TaskQueryController {
    private final TaskQueryService taskQueryService;
    public TaskQueryController(TaskQueryService taskQueryService) { this.taskQueryService = taskQueryService; }

    @GetMapping("/tasks/my")
    public Result<PageResult<TaskVO>> getMyTasks(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size,
        @RequestParam(defaultValue = "ALL") String status,
        @RequestParam(required = false) String keyword
    ) {
        return Result.success(taskQueryService.getMyTasks(page, size, status, keyword));
    }

    @Operation(summary = "我的任务汇总", description = "统计当前登录用户有权访问的全部任务，不受分页或列表筛选影响")
    @GetMapping("/tasks/my/summary")
    public Result<MyTaskSummaryVO> getMyTaskSummary() {
        return Result.success(taskQueryService.getMyTaskSummary());
    }

    @Operation(summary = "查看任务详情", description = "项目成员查看指定任务的详细信息")
    @GetMapping("/tasks/{taskId}")
    public Result<TaskVO> getTaskDetail(@PathVariable Long taskId) {
        TaskVO taskVO = taskQueryService.getTaskDetail(taskId);
        return Result.success(taskVO);
    }

    @Operation(summary = "查看任务提交记录", description = "查看任务历次提交及审核结果")
    @GetMapping("/tasks/{taskId}/submissions")
    public Result<List<TaskSubmissionVO>> getTaskSubmissions(@PathVariable Long taskId) {
        List<TaskSubmissionVO> submissions = taskQueryService.getTaskSubmissions(taskId);
        return Result.success(submissions);
    }

    @Operation(summary = "查看任务评论", description = "项目成员查看指定任务的历史评论")
    @GetMapping("/tasks/{taskId}/comments")
    public Result<List<TaskCommentVO>> getTaskComments(@PathVariable Long taskId) {
        List<TaskCommentVO> comments = taskQueryService.getTaskComments(taskId);
        return Result.success(comments);
    }

    @Operation(summary = "查看项目任务", description = "查看项目中的任务列表，可通过 scope 和 status 进行筛选")
    @GetMapping("/projects/{projectId}/tasks")
    public Result<PageResult<TaskVO>> getProjectTasks(
        @PathVariable Long projectId,
        @RequestParam(defaultValue = "ALL") String scope,
        @RequestParam(defaultValue = "ALL") String status,
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size,
        @RequestParam(required = false) Long assigneeId,
        @RequestParam(defaultValue = "false") boolean unfinishedOnly
    ) {
        var tasks = taskQueryService.getProjectTasks(projectId, scope, status, page, size, assigneeId, unfinishedOnly);
        return Result.success(tasks);
    }
}
