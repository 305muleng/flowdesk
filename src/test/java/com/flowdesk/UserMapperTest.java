package com.flowdesk;

import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void testSelectAll() {
        List<User> users = userMapper.selectList(null);

        System.out.println("查询到的用户数量：" + users.size());

        for (User user : users) {
            System.out.println(
                    "id=" + user.getId()
                            + ", username=" + user.getUsername()
                            + ", realName=" + user.getRealName()
            );
        }
    }
}