package com.flowdesk.validation;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.model.User;

/** 账号层面的业务判定，不代替项目成员关系或项目角色检查。 */
public final class UserRules {

    private UserRules() {}

    public static boolean isActiveOrdinaryUser(User user) {
        return user != null && UserStatus.ACTIVE.equals(user.getStatus()) && "USER".equals(user.getSystemRole());
    }
}
