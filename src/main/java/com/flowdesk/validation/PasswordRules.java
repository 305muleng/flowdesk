package com.flowdesk.validation;

import java.nio.charset.StandardCharsets;

public final class PasswordRules {
    public static final int MAX_BCRYPT_BYTES = 72;
    public static final String BCRYPT_LENGTH_MESSAGE = "密码的UTF-8编码长度不能超过72字节";

    private PasswordRules() {}

    // Null and blank checks belong to the DTO's existing validation constraints.
    public static boolean isWithinBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }
}
