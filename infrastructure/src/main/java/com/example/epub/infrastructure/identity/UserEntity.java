package com.example.epub.infrastructure.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id private UUID id;
    @Column(name="email_normalized", nullable=false, length=320, unique=true) private String email;
    @Column(name="password_hash", length=255) private String passwordHash;
    @Column(name="display_name", nullable=false, length=60) private String displayName;
    @Column(nullable=false, length=240) private String bio;
    @Column(nullable=false, length=16) private String role;
    @Column(nullable=false, length=16) private String status;
    @Column(name="email_verified_at") private Instant emailVerifiedAt;
    @Column(name="password_changed_at", nullable=false) private Instant passwordChangedAt;
    @Version private long revision;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    protected UserEntity() { }
    public UUID id() { return id; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
    public String role() { return role; }
    public String status() { return status; }
}
