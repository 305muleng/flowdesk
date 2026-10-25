package com.flowdesk;

import com.flowdesk.enums.ProjectRole;
import com.flowdesk.enums.SystemRole;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.User;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RoleDefinitionsTest {
    static Stream<Arguments> roleCodes() {
        return Stream.of(
                Arguments.of("USER", true, false),
                Arguments.of("SYSTEM_ADMIN", true, false),
                Arguments.of("PROJECT_MANAGER", false, true),
                Arguments.of("DEVELOPER", false, true),
                Arguments.of(null, false, false),
                Arguments.of("", false, false),
                Arguments.of("UNKNOWN", false, false),
                Arguments.of("user", false, false),
                Arguments.of("developer", false, false),
                Arguments.of(" USER ", false, false));
    }

    @ParameterizedTest
    @MethodSource("roleCodes")
    void systemAndProjectRolesKeepSeparateExactCodeSets(String code, boolean systemKnown, boolean projectKnown) {
        assertEquals(systemKnown, SystemRole.isKnownCode(code));
        assertEquals(projectKnown, ProjectRole.isKnownCode(code));
        assertEquals("USER".equals(code), SystemRole.USER.matches(code));
        assertEquals("SYSTEM_ADMIN".equals(code), SystemRole.SYSTEM_ADMIN.matches(code));
        assertEquals("PROJECT_MANAGER".equals(code), ProjectRole.PROJECT_MANAGER.matches(code));
        assertEquals("DEVELOPER".equals(code), ProjectRole.DEVELOPER.matches(code));
    }

    @Test
    void entityJsonKeepsExistingStringCodes() {
        User user = new User();
        user.setSystemRole(SystemRole.USER.getCode());
        ProjectMember member = new ProjectMember();
        member.setRole(ProjectRole.DEVELOPER.getCode());
        var mapper = new ObjectMapper();
        assertEquals("USER", mapper.readTree(mapper.writeValueAsString(user)).get("systemRole").asString());
        assertEquals("DEVELOPER", mapper.readTree(mapper.writeValueAsString(member)).get("role").asString());
    }

    @Test
    void fixedMapperSqlUsesTheSameRoleCodes() {
        var systemPattern = Pattern.compile("\\bsystem_role\\s*=\\s*'([^']+)'");
        var projectPattern = Pattern.compile("\\brole\\s*=\\s*'([^']+)'");
        int systemChecks = 0;
        int projectChecks = 0;
        for (Class<?> mapper : List.of(ProjectMemberMapper.class, ProjectMapper.class, TaskMapper.class, UserMapper.class)) {
            for (var method : mapper.getDeclaredMethods()) {
                var statements = new ArrayList<String>();
                Select select = method.getAnnotation(Select.class);
                Update update = method.getAnnotation(Update.class);
                if (select != null) statements.addAll(List.of(select.value()));
                if (update != null) statements.addAll(List.of(update.value()));
                for (String statement : statements) {
                    var systemMatches = systemPattern.matcher(statement);
                    while (systemMatches.find()) {
                        assertTrue(SystemRole.isKnownCode(systemMatches.group(1)), method.getName());
                        systemChecks++;
                    }
                    var projectMatches = projectPattern.matcher(statement);
                    while (projectMatches.find()) {
                        assertTrue(ProjectRole.isKnownCode(projectMatches.group(1)), method.getName());
                        projectChecks++;
                    }
                }
            }
        }
        assertTrue(systemChecks > 0);
        assertTrue(projectChecks > 0);
    }
}
