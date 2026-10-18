package com.flowdesk.cache;

import com.flowdesk.vo.ProjectTaskSummaryVO;

public class TaskSummaryCacheReadResult {

    private final ProjectTaskSummaryVO summary;
    private final boolean redisReadFailed;

    public TaskSummaryCacheReadResult(
            ProjectTaskSummaryVO summary,
            boolean redisReadFailed) {
        this.summary = summary;
        this.redisReadFailed = redisReadFailed;
    }

    public ProjectTaskSummaryVO getSummary() {
        return summary;
    }

    public boolean isRedisReadFailed() {
        return redisReadFailed;
    }
}