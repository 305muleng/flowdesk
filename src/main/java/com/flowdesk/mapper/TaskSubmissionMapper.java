package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.TaskSubmission;
import com.flowdesk.vo.TaskSubmissionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskSubmissionMapper
        extends BaseMapper<TaskSubmission> {

    @Select("""
            SELECT COALESCE(MAX(submission_no), 0)
            FROM task_submission
            WHERE task_id = #{taskId}
            """)
    Integer selectMaxSubmissionNo(@Param("taskId") Long taskId);

    @Select("""
        SELECT *
        FROM task_submission
        WHERE task_id = #{taskId}
          AND review_status = 'PENDING'
        ORDER BY submission_no DESC
        LIMIT 1
        """)
    TaskSubmission selectPendingSubmission(@Param("taskId") Long taskId);

    @Select("""
        SELECT
            ts.id AS id,
            ts.submission_no AS submissionNo,

            ts.submitter_id AS submitterId,
            submitter.real_name AS submitterName,

            ts.completion_note AS completionNote,
            ts.result_url AS resultUrl,
            ts.test_note AS testNote,

            ts.review_status AS reviewStatus,

            ts.reviewer_id AS reviewerId,
            reviewer.real_name AS reviewerName,
            ts.review_note AS reviewNote,

            ts.submitted_at AS submittedAt,
            ts.reviewed_at AS reviewedAt

        FROM task_submission ts

        JOIN `user` submitter
            ON ts.submitter_id = submitter.id

        LEFT JOIN `user` reviewer
            ON ts.reviewer_id = reviewer.id

        WHERE ts.task_id = #{taskId}

        ORDER BY ts.submission_no ASC
        """)
    List<TaskSubmissionVO> selectTaskSubmissions(@Param("taskId") Long taskId);
}