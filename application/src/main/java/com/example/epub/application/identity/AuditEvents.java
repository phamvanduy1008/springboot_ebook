package com.example.epub.application.identity;

import java.util.UUID;

public interface AuditEvents {
    enum Action { LOGIN_SUCCEEDED, LOGIN_FAILED, LOGOUT, ACCESS_CHANGED, SESSION_REJECTED, GOOGLE_LOGIN, GOOGLE_LINKED, PASSWORD_CHANGED, PASSWORD_RESET }
    /** Only typed actions and IDs: callers cannot submit arbitrary text or secrets. */
    void record(Action action,UUID actor,UUID target,String requestId);
}
