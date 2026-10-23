package com.flowdesk;

import com.flowdesk.config.PasswordConfig;
import com.flowdesk.controller.UserController;
import com.flowdesk.dto.LoginDTO;
import com.flowdesk.dto.RegisterDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.service.UserService;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.validation.PasswordRules;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationPasswordValidationTest {
    static ValidatorFactory validators;

    @BeforeAll
    static void initializeValidator() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validators.close();
    }

    static Stream<Arguments> passwords() {
        return Stream.of(
                Arguments.of("\u5bc6".repeat(24), true),
                Arguments.of("\u5bc6".repeat(25), false),
                Arguments.of("\uD83D\uDE00".repeat(18), true),
                Arguments.of("\uD83D\uDE00".repeat(19), false),
                Arguments.of("a".repeat(50), true),
                Arguments.of("a".repeat(51), false));
    }

    @ParameterizedTest
    @MethodSource("passwords")
    void dtoRespectsBothCharacterAndUtf8ByteLimits(String password, boolean valid) {
        assertEquals(valid, validators.getValidator().validate(registration(password)).isEmpty());
    }

    @Test
    void oversizedUtf8PasswordReturns400BeforeCallingService() throws Exception {
        UserService service = mock(UserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = "{\"username\":\"alice\",\"realName\":\"Alice\",\"password\":\""
                + "\u5bc6".repeat(30) + "\"}";
        mvc.perform(post("/users/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(PasswordRules.BCRYPT_LENGTH_MESSAGE));
        verifyNoInteractions(service);
    }

    @Test
    void directServiceCallAlsoRejectsOversizedPasswordBeforeDatabaseAccess() {
        UserMapper users = mock(UserMapper.class);
        UserService service = new UserService(users, new PasswordConfig().passwordEncoder(), mock(JwtUtil.class));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(registration("\u5bc6".repeat(30))));
        assertEquals(400, error.getCode());
        verifyNoInteractions(users);
    }

    @Test
    void oversizedLoginPasswordReturnsAuthenticationFailureInsteadOf500() {
        UserMapper users = mock(UserMapper.class);
        UserService service = new UserService(users, new PasswordConfig().passwordEncoder(), mock(JwtUtil.class));
        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword("\u5bc6".repeat(30));
        BusinessException error = assertThrows(BusinessException.class, () -> service.login(dto));
        assertEquals(401, error.getCode());
        verifyNoInteractions(users);
    }

    @Test
    void exactly72Utf8BytesCanBeEncodedAndVerifiedWithoutTruncation() {
        String password = "\u5bc6".repeat(24);
        var encoder = new PasswordConfig().passwordEncoder();
        String hash = encoder.encode(password);
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches("\u5bc6".repeat(23), hash));
    }

    private RegisterDTO registration(String password) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("alice");
        dto.setPassword(password);
        dto.setRealName("Alice");
        return dto;
    }
}
