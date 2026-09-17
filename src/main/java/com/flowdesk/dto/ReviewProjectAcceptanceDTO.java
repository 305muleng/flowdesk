package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class ReviewProjectAcceptanceDTO {

    @NotBlank(message = "验收结果不能为空")
    private String action;

    private String reviewNote;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }
}