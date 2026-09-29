package com.flowdesk.vo;

import java.util.List;

public class MemberAcceptanceVO {
    private Long userId;
    private String userName;
    private String role;
    private Integer completedTaskCount;
    private List<TaskSimpleVO> completedTasks;

    public Integer getCompletedTaskCount() {
        return completedTaskCount;
    }

    public void setCompletedTaskCount(Integer completedTaskCount) {
        this.completedTaskCount = completedTaskCount;
    }

    public List<TaskSimpleVO> getCompletedTasks() {
        return completedTasks;
    }

    public void setCompletedTasks(List<TaskSimpleVO> completedTasks) {
        this.completedTasks = completedTasks;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }
}
