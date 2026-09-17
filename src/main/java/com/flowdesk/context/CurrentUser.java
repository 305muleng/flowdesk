package com.flowdesk.context;

public class CurrentUser {

    private final Long userId;
    private final String systemRole;

    public CurrentUser(Long userId, String systemRole) {
        this.userId = userId;
        this.systemRole = systemRole;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSystemRole() {
        return systemRole;
    }
}