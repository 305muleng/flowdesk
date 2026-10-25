package com.flowdesk;

import com.flowdesk.enums.ReviewAction;
import com.flowdesk.enums.TaskPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BusinessCodesTest {
    static Stream<Arguments> codes() {
        return Stream.of(
                Arguments.of("LOW", true, false),
                Arguments.of("MEDIUM", true, false),
                Arguments.of("HIGH", true, false),
                Arguments.of("APPROVE", false, true),
                Arguments.of("REJECT", false, true),
                Arguments.of(null, false, false),
                Arguments.of("", false, false),
                Arguments.of("low", false, false),
                Arguments.of("approve", false, false),
                Arguments.of("APPROVED", false, false),
                Arguments.of(" HIGH ", false, false));
    }

    @ParameterizedTest
    @MethodSource("codes")
    void taskPrioritiesAndReviewActionsKeepSeparateCodeSets(String code, boolean priority, boolean action) {
        assertEquals(priority, TaskPriority.isKnownCode(code));
        assertEquals(action, ReviewAction.isKnownCode(code));
        assertEquals("MEDIUM".equals(code), TaskPriority.MEDIUM.matches(code));
        assertEquals("APPROVE".equals(code), ReviewAction.APPROVE.matches(code));
        assertEquals("REJECT".equals(code), ReviewAction.REJECT.matches(code));
    }

    @Test
    void wireAndDatabaseCodesRemainTheExistingStrings() {
        assertEquals(Set.of("LOW", "MEDIUM", "HIGH"),
                Arrays.stream(TaskPriority.values()).map(TaskPriority::getCode).collect(Collectors.toSet()));
        assertEquals(Set.of("APPROVE", "REJECT"),
                Arrays.stream(ReviewAction.values()).map(ReviewAction::getCode).collect(Collectors.toSet()));
    }
}
