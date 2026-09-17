package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.ReviewProjectAcceptanceDTO;
import com.flowdesk.service.ProjectAcceptanceService;
import com.flowdesk.vo.ProjectAcceptanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "管理员验收",
        description = "系统管理员查看并审核项目验收申请"
)
@RestController
@RequestMapping("/admin/project-acceptances")
public class AdminProjectAcceptanceController {

    private final ProjectAcceptanceService projectAcceptanceService;

    public AdminProjectAcceptanceController(
            ProjectAcceptanceService projectAcceptanceService) {

        this.projectAcceptanceService =
                projectAcceptanceService;
    }

    @Operation(
            summary = "查看验收申请",
            description = "系统管理员查看项目验收申请，可按审核状态筛选"
    )
    @GetMapping
    public Result<List<ProjectAcceptanceVO>> getAcceptances(@RequestParam(defaultValue = "PENDING") String status) {
        return Result.success(projectAcceptanceService.getAcceptances(status));
    }

    @Operation(
            summary = "审核项目验收",
            description = "系统管理员审核待处理的项目验收申请；通过后项目进入 COMPLETED，驳回后项目返回 IN_PROGRESS"
    )
    @PostMapping("/{acceptanceId}/review")
    public Result<Void> reviewAcceptance(@PathVariable Long acceptanceId, @Valid @RequestBody ReviewProjectAcceptanceDTO dto) {
        projectAcceptanceService.reviewAcceptance(acceptanceId, dto);
        return Result.success("项目验收审核完成", null);
    }
}