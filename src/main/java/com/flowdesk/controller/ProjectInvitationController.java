package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.service.ProjectInvitationService;
import com.flowdesk.vo.InvitationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "项目邀请",
        description = "项目成员邀请的查看、接受和拒绝相关接口"
)
@RestController
@RequestMapping("/invitations")
public class ProjectInvitationController {

    private final ProjectInvitationService projectInvitationService;

    public ProjectInvitationController(
            ProjectInvitationService projectInvitationService) {

        this.projectInvitationService = projectInvitationService;
    }

    @Operation(
            summary = "查看我的项目邀请",
            description = "查看当前登录用户收到的项目邀请，可按状态筛选"
    )
    @GetMapping("/my")
    public Result<List<InvitationVO>> getMyInvitations(@RequestParam(defaultValue = "ALL") String filter) {
        List<InvitationVO> invitations = projectInvitationService.getMyInvitations(filter);
        return Result.success(invitations);
    }

    @Operation(
            summary = "接受项目邀请",
            description = "当前用户接受有效的待处理邀请，并加入对应项目成为开发人员"
    )
    @PostMapping("/{invitationId}/accept")
    public Result<Void> acceptInvitation(
            @PathVariable Long invitationId) {

        projectInvitationService.acceptInvitation(invitationId);

        return Result.success("邀请接受成功", null);
    }

    @Operation(
            summary = "拒绝项目邀请",
            description = "当前用户拒绝仍处于 PENDING 状态的项目邀请"
    )
    @PostMapping("/{invitationId}/reject")
    public Result<Void> rejectInvitation(
            @PathVariable Long invitationId) {

        projectInvitationService.rejectInvitation(invitationId);

        return Result.success("邀请已拒绝", null);
    }
}