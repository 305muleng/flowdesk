package com.flowdesk;

import com.flowdesk.enums.UserStatus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.LoginDTO;
import com.flowdesk.dto.RegisterDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.service.UserService;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.vo.LoginVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private UserService userService;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void registrationAlwaysCreatesActiveNormalUserWithEncodedPassword() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("  alice  ");
        dto.setPassword("secret1");
        dto.setRealName(" Alice ");

        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");

        userService.register(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("alice", saved.getUsername());
        assertEquals("Alice", saved.getRealName());
        assertEquals("USER", saved.getSystemRole());
        assertEquals(UserStatus.ACTIVE, saved.getStatus());
        assertEquals("bcrypt-hash", saved.getPasswordHash());
        assertFalse("secret1".equals(saved.getPasswordHash()));
    }

    @Test
    void normalizedUsernameCannotBypassMinimumLength() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(" a ");
        dto.setPassword("secret1");
        dto.setRealName("Alice");

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.register(dto));

        assertEquals(400, exception.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void disabledUserCannotLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword("secret1");
        User user = new User();
        user.setId(2L);
        user.setPasswordHash("bcrypt-hash");
        user.setStatus(UserStatus.DISABLED);

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret1", "bcrypt-hash")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(dto));

        assertEquals(403, exception.getCode());
        verify(jwtUtil, never()).generateToken(any());
    }

    @Test
    void successfulLoginTokenContainsOnlyStableUserIdInput() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword("secret1");
        User user = new User();
        user.setId(2L);
        user.setUsername("alice");
        user.setRealName("Alice");
        user.setPasswordHash("bcrypt-hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole("USER");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret1", "bcrypt-hash")).thenReturn(true);
        when(jwtUtil.generateToken(2L)).thenReturn("token");

        LoginVO result = userService.login(dto);

        assertEquals("token", result.getToken());
        verify(jwtUtil).generateToken(2L);
    }

    @Test
    void nonexistentUserCannotLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("bob");
        dto.setPassword("test-password");

        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(dto));

        assertEquals(401, exception.getCode());
        verify(jwtUtil, never()).generateToken(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uk_user_username",
            "user.uk_user_username"
    })
    void usernameConflictDuringInsertReturns409(String key) {
        RegisterDTO dto = registrationInput();

        // 模拟提前查询时没有重名，但插入时发生了竞争。
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");

        SQLException sqlException = new SQLException(
                "Duplicate entry 'alice' for key '" + key + "'",
                "23000",
                1062
        );
        when(userMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("insert failed", sqlException));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> userService.register(dto)
        );

        assertEquals(409, error.getCode());
        assertEquals("用户名已存在", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user.PRIMARY",
            "user.uk_user_email",
            "user.uk_user_username_extra"
    })
    void otherDuplicateKeysAreNotReportedAsUsernameConflict(String key) {
        RegisterDTO dto = registrationInput();

        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");

        SQLException sqlException = new SQLException(
                "Duplicate entry 'value' for key '" + key + "'",
                "23000",
                1062
        );
        DuplicateKeyException original =
                new DuplicateKeyException("insert failed", sqlException);

        when(userMapper.insert(any(User.class))).thenThrow(original);

        DuplicateKeyException error = assertThrows(
                DuplicateKeyException.class,
                () -> userService.register(dto)
        );

        assertSame(original, error);
    }

    private RegisterDTO registrationInput() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("alice");
        dto.setPassword("secret1");
        dto.setRealName("Alice");
        return dto;
    }
}
