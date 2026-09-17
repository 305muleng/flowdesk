package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class SendInvitationDTO {

    @NotBlank(message = "被邀请用户名不能为空")
    private String username;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}