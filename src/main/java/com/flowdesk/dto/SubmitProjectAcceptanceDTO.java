package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class SubmitProjectAcceptanceDTO {

    @NotBlank(message = "验收提交说明不能为空")
    private String submissionNote;

    public String getSubmissionNote() {
        return submissionNote;
    }

    public void setSubmissionNote(String submissionNote) {
        this.submissionNote = submissionNote;
    }
}