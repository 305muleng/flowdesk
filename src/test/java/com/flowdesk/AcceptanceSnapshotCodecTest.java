package com.flowdesk;

import com.flowdesk.exception.BusinessException;
import com.flowdesk.snapshot.AcceptanceSnapshotCodec;
import com.flowdesk.vo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AcceptanceSnapshotCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AcceptanceSnapshotCodec codec = new AcceptanceSnapshotCodec(mapper);

    static ProjectAcceptanceDetailVO delivery() {
        var value = new ProjectAcceptanceDetailVO(); value.setId(10L); value.setProjectId(4L);
        value.setProjectName("Original project"); value.setProjectDescription("Original description");
        value.setProjectGoal("Original goal"); value.setRepositoryUrl("https://example.com/A");
        value.setSubmitterId(1L); value.setSubmitterName("Original name");
        value.setTotalTaskCount(2); value.setCompletedTaskCount(1); value.setCancelledTaskCount(1); value.setCompletionRate(50.0);
        var done = new TaskSimpleVO(); done.setTaskId(20L); done.setTitle("Original task"); done.setCompletedAt(LocalDateTime.of(2026,10,23,12,0));
        var member = new MemberAcceptanceVO(); member.setUserId(1L); member.setUserName("Original name");
        member.setRole("PROJECT_MANAGER"); member.setCompletedTaskCount(1); member.setCompletedTasks(List.of(done));
        value.setMembers(List.of(member));
        var cancelled = new CancelledTaskVO(); cancelled.setTaskId(21L); cancelled.setTitle("Cancelled task");
        cancelled.setAssigneeId(1L); cancelled.setAssigneeName("Original name"); cancelled.setCancelReason("Original reason");
        cancelled.setCancelledAt(LocalDateTime.of(2026,10,23,11,0));value.setCancelledTasks(List.of(cancelled));
        return value;
    }

    @Test void roundTripFreezesDeliveryButPreservesLiveWorkflowFields() {
        var original = delivery(); String json = codec.encode(original);
        original.setProjectName("Changed"); original.getMembers().get(0).setUserName("Changed");
        original.getMembers().get(0).getCompletedTasks().get(0).setTitle("Changed");
        var current = delivery(); current.setProjectStatus("ARCHIVED"); current.setReviewStatus("REJECTED");
        current.setReviewNote("Review after capture"); current.setRepositoryUrl("https://example.com/B");
        codec.apply(1,json,current);
        assertEquals("Original project",current.getProjectName());
        assertEquals("https://example.com/A",current.getRepositoryUrl());
        assertEquals("Original name",current.getMembers().get(0).getUserName());
        assertEquals("Original task",current.getMembers().get(0).getCompletedTasks().get(0).getTitle());
        assertEquals("Original reason",current.getCancelledTasks().get(0).getCancelReason());
        assertEquals(50.0,current.getCompletionRate());
        assertEquals("ARCHIVED",current.getProjectStatus()); assertEquals("REJECTED",current.getReviewStatus());
        assertEquals("Review after capture",current.getReviewNote());
    }
    @ParameterizedTest @ValueSource(strings={"", "null", "{}", "not-json", "{\"projectId\":4}"})
    void brokenDocumentsFailExplicitly(String json) {
        assertEquals(500,assertThrows(BusinessException.class,()->codec.apply(1,json,delivery())).getCode());
    }
    @Test void partialOrUnsupportedMetadataAndWrongOwnershipFail() {
        String json=codec.encode(delivery());
        for(Integer version:new Integer[]{null,0,2}) assertThrows(BusinessException.class,()->codec.apply(version,json,delivery()));
        assertThrows(BusinessException.class,()->codec.apply(1,null,delivery()));
        var foreign=delivery();foreign.setProjectId(8L);assertThrows(BusinessException.class,()->codec.apply(1,json,foreign));
        foreign.setProjectId(4L);foreign.setSubmitterId(9L);assertThrows(BusinessException.class,()->codec.apply(1,json,foreign));
    }
    @Test void missingNestedFieldsAndInconsistentCountsFail() {
        String json=codec.encode(delivery());
        assertThrows(BusinessException.class,()->codec.apply(1,json.replace("\"completedTasks\"", "\"missingTasks\""),delivery()));
        var invalid=delivery();invalid.setTotalTaskCount(99);assertThrows(BusinessException.class,()->codec.encode(invalid));
    }
    @Test void emptyAndAllCancelledProjectsKeepExistingRateRules() {
        var empty=delivery();empty.setTotalTaskCount(0);empty.setCompletedTaskCount(0);empty.setCancelledTaskCount(0);
        empty.setCompletionRate(0.0);empty.setMembers(List.of());empty.setCancelledTasks(List.of());
        codec.apply(1,codec.encode(empty),delivery());
        var cancelled=delivery();cancelled.setTotalTaskCount(1);cancelled.setCompletedTaskCount(0);cancelled.setCompletionRate(0.0);
        cancelled.getMembers().get(0).setCompletedTasks(List.of());cancelled.getMembers().get(0).setCompletedTaskCount(0);
        var result=delivery();codec.apply(1,codec.encode(cancelled),result);assertEquals(0.0,result.getCompletionRate());
    }
    @Test void rawPersistedJsonIsNotDuplicatedInApiResponse() {
        var detail=delivery();detail.setSnapshotJson("private-raw-document");detail.setSnapshotVersion(1);
        detail.setDataSource("SUBMISSION_SNAPSHOT");
        String response=mapper.writeValueAsString(detail);
        assertFalse(response.contains("snapshotJson"));assertFalse(response.contains("private-raw-document"));
        assertTrue(response.contains("SUBMISSION_SNAPSHOT"));
    }
}
