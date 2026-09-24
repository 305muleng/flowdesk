package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.*;
import com.flowdesk.service.ProjectInvitationService;
import com.flowdesk.service.ProjectService;
import com.flowdesk.service.TaskService;
import com.flowdesk.vo.ProjectMemberVO;
import com.flowdesk.vo.ProjectVO;
import com.flowdesk.vo.TaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "项目管理",
        description = "项目创建、详情、成员、邀请、任务以及项目生命周期相关接口"
)
@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectInvitationService projectInvitationService;
    private final TaskService taskService;

    public ProjectController(
            ProjectService projectService,
            ProjectInvitationService projectInvitationService,
            TaskService taskService) {

        this.projectService = projectService;
        this.projectInvitationService = projectInvitationService;
        this.taskService = taskService;
    }

    @Operation(
            summary = "创建项目",
            description = "当前登录用户创建新项目，并自动成为该项目的项目负责人"
    )
    @PostMapping
    public Result<Long> createProject(@Valid @RequestBody CreateProjectDTO dto) {
        Long projectId = projectService.createProject(dto);
        return Result.success("项目创建成功", projectId);
    }

    @Operation(summary = "查看我的项目", description = "查看当前登录用户作为有效成员参与的全部项目")
    @GetMapping
    public Result<List<ProjectVO>> getMyProjects() {
        return Result.success(projectService.getMyProjects());
    }

    @Operation(
            summary = "查看项目详情",
            description = "项目成员查看指定项目的基本信息"
    )
    @GetMapping("/{projectId}")
    public Result<ProjectVO> getProjectDetail(@PathVariable Long projectId) {
        ProjectVO projectVO = projectService.getProjectDetail(projectId);
        return Result.success(projectVO);
    }

    @Operation(
            summary = "邀请成员加入项目",
            description = "项目负责人向指定用户发送项目邀请"
    )
    @PostMapping("/{projectId}/invitations")
    public Result<Long> sendInvitation(@PathVariable Long projectId, @Valid @RequestBody SendInvitationDTO dto) {
        Long invitationId = projectInvitationService.sendInvitation(projectId, dto);
        return Result.success("邀请发送成功", invitationId);
    }

    @Operation(
            summary = "查看项目成员",
            description = "查看指定项目当前的成员列表及项目角色"
    )
    @GetMapping("/{projectId}/members")
    public Result<List<ProjectMemberVO>> getProjectMembers(@PathVariable Long projectId) {
        List<ProjectMemberVO> members = projectService.getProjectMembers(projectId);
        return Result.success(members);
    }

    @Operation(
            summary = "创建正式任务",
            description = "项目负责人在项目中创建正式任务，可指定负责人、优先级和截止时间"
    )
    @PostMapping("/{projectId}/tasks")
    public Result<Long> createTask(@PathVariable Long projectId, @Valid @RequestBody CreateTaskDTO dto) {
        Long taskId = taskService.createTask(projectId, dto);
        return Result.success("任务创建成功", taskId);
    }

    @Operation(
            summary = "查看项目任务",
            description = "查看项目中的任务列表，可通过 scope 和 status 进行筛选"
    )
    @GetMapping("/{projectId}/tasks")
    public Result<List<TaskVO>> getProjectTasks(@PathVariable Long projectId, @RequestParam(defaultValue = "ALL") String scope, @RequestParam(defaultValue = "ALL") String status) {
        List<TaskVO> tasks = taskService.getProjectTasks(projectId, scope, status);
        return Result.success(tasks);
    }

    @Operation(
            summary = "启动项目",
            description = "项目负责人将准备中的项目从 PREPARING 启动为 IN_PROGRESS"
    )
    @PostMapping("/{projectId}/start")
    public Result<Void> startProject(@PathVariable Long projectId) {
        projectService.startProject(projectId);
        return Result.success("项目启动成功", null);
    }

    @Operation(
            summary = "取消项目",
            description = "项目负责人取消准备中或进行中的项目，并填写取消原因"
    )
    @PostMapping("/{projectId}/cancel")
    public Result<Void> cancelProject(@PathVariable Long projectId, @Valid @RequestBody CancelProjectDTO dto) {
        projectService.cancelProject(projectId, dto);
        return Result.success("项目取消成功", null);
    }

    @Operation(
            summary = "归档项目",
            description = "项目负责人将已完成的 COMPLETED 项目归档为 ARCHIVED，归档后项目进入只读状态"
    )
    @PostMapping("/{projectId}/archive")
    public Result<Void> archiveProject(@PathVariable Long projectId) {
        projectService.archiveProject(projectId);
        return Result.success("项目归档成功", null);
    }

    @Operation(
            summary = "移除项目成员",
            description = "项目负责人直接移除开发人员；被移除成员不能存在未完成任务"
    )
    @PostMapping("/{projectId}/members/{userId}/remove")
    public Result<Void> removeProjectMember(@PathVariable Long projectId, @PathVariable Long userId, @RequestBody(required = false) RemoveProjectMemberDTO dto) {
        projectService.removeProjectMember(projectId, userId, dto);
        return Result.success("成员移除成功", null);
    }
}
