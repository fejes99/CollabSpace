package com.collabspace.authworkspace.adapter.out.persistence.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
public class RefreshTokenEntity {

	@Id
	private UUID id;

	@OnDelete(action = OnDeleteAction.CASCADE)
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "token_hash", unique = true, nullable = false, length = 64)
	private String tokenHash;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "user_agent")
	private String userAgent;

	@Column(name = "ip_address")
	private String ipAddress;

	public RefreshTokenEntity() {
	}

	public RefreshTokenEntity(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt,
			String userAgent, String ipAddress) {
		this.id = id;
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.userAgent = userAgent;
		this.ipAddress = ipAddress;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getIpAddress() {
		return ipAddress;
	}

}
