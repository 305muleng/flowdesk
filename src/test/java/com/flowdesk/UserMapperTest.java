package com.flowdesk;

import com.flowdesk.support.IntegrationTest;

import com.flowdesk.enums.UserStatus;

import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@ActiveProfiles("test")
@SpringBootTest
@IntegrationTest
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDatabaseIsolation() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

        assertEquals("flowdesk_test", database);
    }

    @Test
    @Transactional
    void testSelectAll() {
        // 1. 安全检查：确保不会误操作开发数据库
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals("flowdesk_test", database);

        String currentUser = jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String.class);
        assertEquals("flowdesk_test_user@%", currentUser);

        // 2. 准备测试数据
        User user = new User();
        user.setUsername("mapper_test_" + UUID.randomUUID());
        user.setPasswordHash("test-only-hash");
        user.setRealName("测试用户");
        user.setSystemRole("USER");
        user.setStatus(UserStatus.ACTIVE);

        // 3. 插入测试用户
        int rows = userMapper.insert(user);

        assertEquals(1, rows);
        assertNotNull(user.getId());

        // 4. 查询用户
        List<User> users = userMapper.selectList(null);

        // 5. 验证查询结果确实包含刚才插入的用户
        assertTrue(
            users
                .stream()
                .anyMatch(
                    u ->
                        user.getId().equals(u.getId()) &&
                        user.getUsername().equals(u.getUsername()) &&
                        "测试用户".equals(u.getRealName())
                )
        );
    }

    @Test
    @Transactional
    void duplicateUsernameProducesExpectedDatabaseException() {
        // 写入前确认连接的是测试数据库。
        String database = jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class
        );
        assertEquals("flowdesk_test", database);

        String currentUser = jdbcTemplate.queryForObject(
                "SELECT CURRENT_USER()", String.class
        );
        assertEquals("flowdesk_test_user@%", currentUser);

        // 随机用户名，避免与已有测试数据冲突。
        String username = "dup_" + UUID.randomUUID();

        // 第一次插入成功。
        userMapper.insert(newRegistrationTestUser(username));

        // 第二次使用相同用户名，让真实数据库拒绝插入。
        DuplicateKeyException error = assertThrows(
                DuplicateKeyException.class,
                () -> userMapper.insert(newRegistrationTestUser(username))
        );

        // 检查底层数据库异常的类型和错误码。
        Throwable cause = error.getMostSpecificCause();
        SQLException sqlException = assertInstanceOf(SQLException.class, cause);
        assertEquals(1062, sqlException.getErrorCode());

        // 检查真实错误文本能否被当前业务代码识别。
        String message = sqlException.getMessage();
        assertNotNull(message);
        assertTrue(
                message.endsWith("for key 'uk_user_username'")
                        || message.endsWith("for key 'user.uk_user_username'"),
                message
        );
    }

    private User newRegistrationTestUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("test-only-hash");
        user.setRealName("注册冲突测试");
        user.setSystemRole("USER");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
