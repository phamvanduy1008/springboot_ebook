package com.example.epub.application.identity;

import java.util.UUID;

public interface AccessChanges {
    /** Atomically recheck administrator access and protect the last active administrator. */
    void change(UUID actor,UUID target,String role,String status,String requestId);
}
