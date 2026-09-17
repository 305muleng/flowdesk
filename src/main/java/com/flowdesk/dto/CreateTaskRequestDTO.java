package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class CreateTaskRequestDTO {

    @NotBlank(message = "任务申请标题不能为空")
    @Size(max = 200, message = "任务申请标题不能超过200个字符")
    private String title;

    private String description;

    private String goal;

    private LocalDateTime suggestedDeadline;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public LocalDateTime getSuggestedDeadline() {
        return suggestedDeadline;
    }

    public void setSuggestedDeadline(LocalDateTime suggestedDeadline) {
        this.suggestedDeadline = suggestedDeadline;
    }
}