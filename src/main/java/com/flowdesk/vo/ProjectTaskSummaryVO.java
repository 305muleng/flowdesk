package com.flowdesk.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public class ProjectTaskSummaryVO {

    private final Long projectId;
    private final long totalTaskCount;
    private final long todoTaskCount;
    private final long inProgressTaskCount;
    private final long reviewTaskCount;
    private final long doneTaskCount;
    private final long cancelledTaskCount;
    private final BigDecimal completionRate;

    @JsonCreator
    public ProjectTaskSummaryVO(
            @JsonProperty("projectId") Long projectId,
            @JsonProperty("totalTaskCount") long totalTaskCount,
            @JsonProperty("todoTaskCount") long todoTaskCount,
            @JsonProperty("inProgressTaskCount") long inProgressTaskCount,
            @JsonProperty("reviewTaskCount") long reviewTaskCount,
            @JsonProperty("doneTaskCount") long doneTaskCount,
            @JsonProperty("cancelledTaskCount") long cancelledTaskCount,
            @JsonProperty("completionRate") BigDecimal completionRate) {
        this.projectId = projectId;
        this.totalTaskCount = totalTaskCount;
        this.todoTaskCount = todoTaskCount;
        this.inProgressTaskCount = inProgressTaskCount;
        this.reviewTaskCount = reviewTaskCount;
        this.doneTaskCount = doneTaskCount;
        this.cancelledTaskCount = cancelledTaskCount;
        this.completionRate = completionRate;
    }

    public Long getProjectId() {
        return projectId;
    }

    public long getTotalTaskCount() {
        return totalTaskCount;
    }

    public long getTodoTaskCount() {
        return todoTaskCount;
    }

    public long getInProgressTaskCount() {
        return inProgressTaskCount;
    }

    public long getReviewTaskCount() {
        return reviewTaskCount;
    }

    public long getDoneTaskCount() {
        return doneTaskCount;
    }

    public long getCancelledTaskCount() {
        return cancelledTaskCount;
    }

    public BigDecimal getCompletionRate() {
        return completionRate;
    }
}