package com.flowdesk.vo;

public class MemberCandidateVO {
    private Long userId;
    private String username;
    private String realName;
    private String userStatus;
    private String candidateStatus;
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getRealName() { return realName; }
    public void setRealName(String value) { realName = value; }
    public String getUserStatus() { return userStatus; }
    public void setUserStatus(String value) { userStatus = value; }
    public String getCandidateStatus() { return candidateStatus; }
    public void setCandidateStatus(String value) { candidateStatus = value; }
}
