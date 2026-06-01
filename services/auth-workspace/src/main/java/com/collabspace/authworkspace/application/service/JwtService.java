package com.collabspace.authworkspace.application.service;

import com.collabspace.authworkspace.domain.model.WorkspaceMembership;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

	private static final int ACCESS_TOKEN_TTL_SECONDS = 900;

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final RSAKey rsaKey;

	private final JwtProperties jwtProperties;

	private final SecureRandom secureRandom = new SecureRandom();

	public JwtService(RSAKey rsaKey, JwtProperties jwtProperties) {
		this.rsaKey = rsaKey;
		this.jwtProperties = jwtProperties;
	}

	public String issueAccessToken(String userId, List<WorkspaceMembership> memberships) {
		Instant now = Instant.now();
		List<Map<String, String>> membershipClaims = memberships.stream()
			.map(m -> Map.of("workspaceId", m.workspaceId(), "role", m.role()))
			.toList();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("user:" + userId)
			.issuer(jwtProperties.issuer())
			.audience(jwtProperties.audience())
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)))
			.jwtID(UUID.randomUUID().toString())
			.claim("userId", userId)
			.claim("memberships", membershipClaims)
			.build();
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build();
		SignedJWT signedJwt = new SignedJWT(header, claims);
		try {
			signedJwt.sign(new RSASSASigner(rsaKey));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("JWT signing failed", ex);
		}
		return signedJwt.serialize();
	}

	public RefreshTokenPair issueRefreshToken() {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		this.secureRandom.nextBytes(bytes);
		String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String hash = sha256Hex(bytes);
		return new RefreshTokenPair(plaintext, hash);
	}

	private String sha256Hex(byte[] input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
