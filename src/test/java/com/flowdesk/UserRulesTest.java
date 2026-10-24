package com.flowdesk;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.model.User;
import com.flowdesk.validation.UserRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class UserRulesTest {

    @ParameterizedTest
    @CsvSource({
        "ACTIVE,USER,true",
        "DISABLED,USER,false",
        "ACTIVE,SYSTEM_ADMIN,false",
        "DISABLED,SYSTEM_ADMIN,false",
        ",USER,false",
        "ACTIVE,,false",
        "ACTIVE,UNKNOWN,false"
    })
    void onlyAnActiveOrdinaryAccountCanParticipateInProjectWork(String status, String role, boolean expected) {
        User user = new User();
        user.setStatus(status == null ? null : UserStatus.valueOf(status));
        user.setSystemRole(role);
        assertEquals(expected, UserRules.isActiveOrdinaryUser(user));
    }

    @Test
    void missingAccountCannotParticipateInProjectWork() {
        assertFalse(UserRules.isActiveOrdinaryUser(null));
    }
}
