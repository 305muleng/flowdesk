package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

public class SubmitTaskDTO {

    @NotBlank(message = "完成说明不能为空")
    private String completionNote;

    private String resultUrl;

    @NotBlank(message = "测试说明不能为空")
    private String testNote;

    public String getCompletionNote() {
        return completionNote;
    }

    public void setCompletionNote(String completionNote) {
        this.completionNote = completionNote;
    }

    public String getResultUrl() {
        return resultUrl;
    }

    public void setResultUrl(String resultUrl) {
        this.resultUrl = resultUrl;
    }

    public String getTestNote() {
        return testNote;
    }

    public void setTestNote(String testNote) {
        this.testNote = testNote;
    }
}