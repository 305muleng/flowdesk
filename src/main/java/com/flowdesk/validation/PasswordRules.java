package com.flowdesk.validation;

import java.nio.charset.StandardCharsets;

public final class PasswordRules {
    public static final int MAX_BCRYPT_BYTES = 72;
    public static final String BCRYPT_LENGTH_MESSAGE = "密码的UTF-8编码长度不能超过72字节";

    private PasswordRules() {}

    // null 和空白值由 DTO 中已有的校验约束负责检查。
    public static boolean isWithinBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }
}
