package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SubmitProjectAcceptanceDTO {

    @NotBlank(message = "验收提交说明不能为空")
    private String submissionNote;

    @Size(max = 500, message = "仓库链接长度不能超过500个字符")
    private String repositoryUrl;

    @Size(max = 500, message = "部署链接长度不能超过500个字符")
    private String deployUrl;

    @Size(max = 500, message = "文档链接长度不能超过500个字符")
    private String documentUrl;

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

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public String getSubmissionNote() {
        return submissionNote;
    }

    public void setSubmissionNote(String submissionNote) {
        this.submissionNote = submissionNote;
    }
}