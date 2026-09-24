package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateMemberLeaveRequestDTO {

    @NotBlank(message = "退出原因不能为空")
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}