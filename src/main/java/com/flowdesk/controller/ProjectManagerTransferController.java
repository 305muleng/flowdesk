package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.CreateProjectManagerTransferDTO;
import com.flowdesk.service.ProjectManagerTransferService;
import com.flowdesk.vo.ProjectManagerTransferVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "项目负责人转让",
        description = "项目负责人转让的发起、接受、拒绝和撤回"
)
@RestController
@RequestMapping("/projects/{projectId}/manager-transfers")
public class ProjectManagerTransferController {

    private final ProjectManagerTransferService transferService;

    public ProjectManagerTransferController(
            ProjectManagerTransferService transferService) {

        this.transferService = transferService;
    }

    @Operation(
            summary = "发起项目负责人转让",
            description = "当前项目负责人发起负责人转让，目标用户确认后才真正完成转让"
    )
    @PostMapping
    public Result<Long> createTransfer(@PathVariable Long projectId, @Valid @RequestBody CreateProjectManagerTransferDTO dto) {
        Long transferId = transferService.createTransfer(projectId, dto);
        return Result.success("项目负责人转让已发起", transferId);
    }

    @Operation(
            summary = "接受项目负责人转让",
            description = "被转让用户接受负责人角色，并完成新旧负责人的项目角色切换"
    )
    @PostMapping("/{transferId}/accept")
    public Result<Void> acceptTransfer(@PathVariable Long projectId, @PathVariable Long transferId) {
        transferService.acceptTransfer(projectId, transferId);
        return Result.success("项目负责人转让成功", null);
    }

    @Operation(
            summary = "拒绝项目负责人转让",
            description = "被转让用户拒绝负责人角色"
    )
    @PostMapping("/{transferId}/reject")
    public Result<Void> rejectTransfer(@PathVariable Long projectId, @PathVariable Long transferId) {
        transferService.rejectTransfer(projectId, transferId);
        return Result.success("已拒绝项目负责人转让", null);
    }

    @Operation(
            summary = "撤回项目负责人转让",
            description = "原项目负责人撤回尚未处理的负责人转让"
    )
    @PostMapping("/{transferId}/cancel")
    public Result<Void> cancelTransfer(@PathVariable Long projectId, @PathVariable Long transferId) {
        transferService.cancelTransfer(projectId, transferId);
        return Result.success("项目负责人转让已撤回", null);
    }

    @Operation(
            summary = "查看项目负责人转让详情",
            description = "转让双方或当前项目负责人查看负责人转让详情"
    )
    @GetMapping("/{transferId}")
    public Result<ProjectManagerTransferVO> getTransferDetail(@PathVariable Long projectId, @PathVariable Long transferId) {
        ProjectManagerTransferVO vo = transferService.getTransferDetail(projectId, transferId);
        return Result.success(vo);
    }
}