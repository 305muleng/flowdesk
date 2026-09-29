package com.flowdesk.vo;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectAcceptanceDetailVO {
    private Long id;
    private Integer acceptanceNo;
    private String submissionNote;
    private String reviewStatus;
    private LocalDateTime submittedAt;

    private Long projectId;
    private String projectName;
    private String projectDescription;
    private String projectGoal;

    private Integer totalTaskCount;
    private Integer completedTaskCount;
    private Integer cancelledTaskCount;
    private Double completionRate;

    private List<MemberAcceptanceVO> members;
    private List<CancelledTaskVO> cancelledTasks;

    private Long submitterId;
    private String submitterName;

    private Long reviewerId;
    private String reviewerName;
    private String reviewNote;
    private LocalDateTime reviewedAt;

    private String projectStatus;

    private String repositoryUrl;
    private String deployUrl;
    private String documentUrl;

    private List<ProjectAcceptanceVO> acceptanceHistory;

    public List<ProjectAcceptanceVO> getAcceptanceHistory() {
        return acceptanceHistory;
    }

    public void setAcceptanceHistory(List<ProjectAcceptanceVO> acceptanceHistory) {
        this.acceptanceHistory = acceptanceHistory;
    }

    public String getDeployUrl() {
        return deployUrl;
    }

    public void setDeployUrl(String deployUrl) {
        this.deployUrl = deployUrl;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public void setDocumentUrl(String documentUrl) {
        this.documentUrl = documentUrl;
    }

    public String getProjectStatus() {
        return projectStatus;
    }

    public void setProjectStatus(String projectStatus) {
        this.projectStatus = projectStatus;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public void setReviewerName(String reviewerName) {
        this.reviewerName = reviewerName;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Long getSubmitterId() {
        return submitterId;
    }

    public void setSubmitterId(Long submitterId) {
        this.submitterId = submitterId;
    }

    public String getSubmitterName() {
        return submitterName;
    }

    public void setSubmitterName(String submitterName) {
        this.submitterName = submitterName;
    }

    public Integer getAcceptanceNo() {
        return acceptanceNo;
    }

    public void setAcceptanceNo(Integer acceptanceNo) {
        this.acceptanceNo = acceptanceNo;
    }

    public Integer getCancelledTaskCount() {
        return cancelledTaskCount;
    }

    public void setCancelledTaskCount(Integer cancelledTaskCount) {
        this.cancelledTaskCount = cancelledTaskCount;
    }

    public List<CancelledTaskVO> getCancelledTasks() {
        return cancelledTasks;
    }

    public void setCancelledTasks(List<CancelledTaskVO> cancelledTasks) {
        this.cancelledTasks = cancelledTasks;
    }

    public Integer getCompletedTaskCount() {
        return completedTaskCount;
    }

    public void setCompletedTaskCount(Integer completedTaskCount) {
        this.completedTaskCount = completedTaskCount;
    }

    public Double getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(Double completionRate) {
        this.completionRate = completionRate;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<MemberAcceptanceVO> getMembers() {
        return members;
    }

    public void setMembers(List<MemberAcceptanceVO> members) {
        this.members = members;
    }

    public String getProjectDescription() {
        return projectDescription;
    }

    public void setProjectDescription(String projectDescription) {
        this.projectDescription = projectDescription;
    }

    public String getProjectGoal() {
        return projectGoal;
    }

    public void setProjectGoal(String projectGoal) {
        this.projectGoal = projectGoal;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getSubmissionNote() {
        return submissionNote;
    }

    public void setSubmissionNote(String submissionNote) {
        this.submissionNote = submissionNote;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Integer getTotalTaskCount() {
        return totalTaskCount;
    }

    public void setTotalTaskCount(Integer totalTaskCount) {
        this.totalTaskCount = totalTaskCount;
    }
}
