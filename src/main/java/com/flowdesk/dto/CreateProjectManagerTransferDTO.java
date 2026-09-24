package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateProjectManagerTransferDTO {

    @NotBlank(message = "目标用户不能为空")
    private String targetUsername;

    @NotBlank(message = "原负责人处理方式不能为空")
    private String oldManagerAction;

    @NotBlank(message = "当前密码不能为空")
    private String currentPassword;

    public String getTargetUsername() {
        return targetUsername;
    }

    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }

    public String getOldManagerAction() {
        return oldManagerAction;
    }

    public void setOldManagerAction(String oldManagerAction) {
        this.oldManagerAction = oldManagerAction;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }
}