package com.flowdesk;

import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("test")
@SpringBootTest
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDatabaseIsolation() {
        String database = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        assertEquals("flowdesk_test", database);
    }

    @Test
    @Transactional
    void testSelectAll() {

        // 1. 安全检查：确保不会误操作开发数据库
        String database = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );
        assertEquals("flowdesk_test", database);

        String currentUser = jdbcTemplate.queryForObject(
                "SELECT CURRENT_USER()",
                String.class
        );
        assertEquals("flowdesk_test_user@%", currentUser);

        // 2. 准备测试数据
        User user = new User();
        user.setUsername("mapper_test_" + UUID.randomUUID());
        user.setPasswordHash("test-only-hash");
        user.setRealName("测试用户");
        user.setSystemRole("USER");
        user.setStatus("ACTIVE");

        // 3. 插入测试用户
        int rows = userMapper.insert(user);

        assertEquals(1, rows);
        assertNotNull(user.getId());

        // 4. 查询用户
        List<User> users = userMapper.selectList(null);

        // 5. 验证查询结果确实包含刚才插入的用户
        assertTrue(
                users.stream().anyMatch(
                        u -> user.getId().equals(u.getId())
                                && user.getUsername().equals(u.getUsername())
                                && "测试用户".equals(u.getRealName())
                )
        );
    }
}