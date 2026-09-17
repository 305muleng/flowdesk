package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.ProjectAcceptance;
import com.flowdesk.vo.ProjectAcceptanceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectAcceptanceMapper
        extends BaseMapper<ProjectAcceptance> {

    @Select("""
            SELECT COALESCE(MAX(acceptance_no), 0)
            FROM project_acceptance
            WHERE project_id = #{projectId}
            """)
    Integer selectMaxAcceptanceNo(@Param("projectId") Long projectId);

    @Select("""
        SELECT
            pa.id AS id,

            pa.project_id AS projectId,
            p.name AS projectName,

            pa.acceptance_no AS acceptanceNo,

            pa.submitter_id AS submitterId,
            submitter.real_name AS submitterName,

            pa.submission_note AS submissionNote,
            pa.review_status AS reviewStatus,

            pa.reviewer_id AS reviewerId,
            reviewer.real_name AS reviewerName,
            pa.review_note AS reviewNote,

            pa.submitted_at AS submittedAt,
            pa.reviewed_at AS reviewedAt

        FROM project_acceptance pa

        JOIN project p
            ON pa.project_id = p.id

        JOIN `user` submitter
            ON pa.submitter_id = submitter.id

        LEFT JOIN `user` reviewer
            ON pa.reviewer_id = reviewer.id

        WHERE (
            #{status} = 'ALL'
            OR pa.review_status = #{status}
        )

        ORDER BY pa.submitted_at DESC
        """)
    List<ProjectAcceptanceVO> selectAcceptances(@Param("status") String status);
}