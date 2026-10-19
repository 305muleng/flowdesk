package com.flowdesk.vo;

public record MyTaskSummaryVO(
    long totalTaskCount,
    long todoTaskCount,
    long inProgressTaskCount,
    long reviewTaskCount,
    long doneTaskCount,
    long unfinishedTaskCount,
    long nonCancelledTaskCount,
    long dueSoonTaskCount
) {
    public static MyTaskSummaryVO empty() {
        return new MyTaskSummaryVO(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
