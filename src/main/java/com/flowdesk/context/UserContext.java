package com.flowdesk.context;

public class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER =
            new ThreadLocal<>();

    public static void set(CurrentUser currentUser) {
        HOLDER.set(currentUser);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void remove() {
        HOLDER.remove();
    }
}