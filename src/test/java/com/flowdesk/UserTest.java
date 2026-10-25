package com.flowdesk;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.model.User;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

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
        assertEquals(expected, user.isActiveOrdinaryUser());
    }

    @Test
    void accountPredicateDoesNotAddAJsonProperty() {
        User user = new User();
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole("USER");
        assertTrue(user.isActiveOrdinaryUser());

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(user));
        assertFalse(json.has("activeOrdinaryUser"));
        assertTrue(json.has("status"));
        assertTrue(json.has("systemRole"));
    }
}
