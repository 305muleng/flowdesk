package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.CreateMemberLeaveRequestDTO;
import com.flowdesk.dto.ReviewMemberLeaveRequestDTO;
import com.flowdesk.service.MemberLeaveRequestService;
import com.flowdesk.vo.MemberLeaveRequestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "成员退出申请",
        description = "开发人员提交、撤回退出申请以及项目负责人审批退出申请相关接口"
)
@RestController
@RequestMapping("/projects/{projectId}/leave-requests")
public class MemberLeaveRequestController {

    private final MemberLeaveRequestService memberLeaveRequestService;

    public MemberLeaveRequestController(
            MemberLeaveRequestService memberLeaveRequestService) {

        this.memberLeaveRequestService =
                memberLeaveRequestService;
    }

    @Operation(
            summary = "提交退出项目申请",
            description = "开发人员向当前项目负责人提交退出项目申请"
    )
    @PostMapping
    public Result<Long> createLeaveRequest(@PathVariable Long projectId, @Valid @RequestBody CreateMemberLeaveRequestDTO dto) {
        Long requestId = memberLeaveRequestService.createLeaveRequest(projectId, dto);
        return Result.success("退出申请提交成功", requestId);
    }

    @Operation(
            summary = "撤回退出项目申请",
            description = "开发人员撤回自己仍处于 PENDING 且未过期的退出申请"
    )
    @PostMapping("/{requestId}/cancel")
    public Result<Void> cancelLeaveRequest(@PathVariable Long projectId, @PathVariable Long requestId) {
        memberLeaveRequestService.cancelLeaveRequest(projectId, requestId);
        return Result.success("退出申请已撤回", null);
    }

    @Operation(
            summary = "查看退出申请详情",
            description = "退出申请人或项目负责人查看指定退出申请的当前状态和详细信息"
    )
    @GetMapping("/{requestId}")
    public Result<MemberLeaveRequestVO> getLeaveRequestDetail(@PathVariable Long projectId, @PathVariable Long requestId) {
        return Result.success(memberLeaveRequestService.getLeaveRequestDetail(projectId, requestId));
    }

    @Operation(
            summary = "审批退出项目申请",
            description = "项目负责人批准或拒绝开发人员的退出项目申请"
    )
    @PostMapping("/{requestId}/review")
    public Result<Void> reviewLeaveRequest(@PathVariable Long projectId, @PathVariable Long requestId, @Valid @RequestBody ReviewMemberLeaveRequestDTO dto) {
        memberLeaveRequestService.reviewLeaveRequest(projectId, requestId, dto);
        return Result.success("退出申请审批完成", null);
    }
}