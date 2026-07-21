package com.collabspace.authworkspace.application.service;

import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

	private static final int ACCESS_TOKEN_TTL_SECONDS = 900;

	public static final int REFRESH_TOKEN_TTL_SECONDS = 604800;

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final RSAKey rsaKey;

	private final JwtProperties jwtProperties;

	private final Clock clock;

	private final ObjectMapper objectMapper;

	private final SecureRandom secureRandom = new SecureRandom();

	public JwtService(RSAKey rsaKey, JwtProperties jwtProperties, Clock clock, ObjectMapper objectMapper) {
		this.rsaKey = rsaKey;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	public AccessToken issueAccessToken(UUID userId, List<WorkspaceMembership> memberships) {
		Instant now = clock.instant();
		String jti = UUID.randomUUID().toString();
		List<Map<String, String>> membershipClaims = memberships.stream()
			.map(m -> Map.of("workspaceId", m.workspaceId().toString(), "role", m.role().getValue()))
			.toList();

		// Serialized to a JSON string, not a nested array claim: API Gateway's JWT
		// authorizer maps claims to headers via $context.authorizer.jwt.claims.*, which
		// only supports string/number/boolean claim values — an array claim cannot be
		// forwarded as X-User-Workspaces.
		String membershipsJson = objectMapper.writeValueAsString(membershipClaims);

		JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("user:" + userId)
			.issuer(jwtProperties.issuer())
			.audience(jwtProperties.audience())
			.claim("iat", now.getEpochSecond())
			.claim("exp", now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS).getEpochSecond())
			.jwtID(jti)
			.claim("userId", userId.toString())
			.claim("memberships", membershipsJson)
			.build();

		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build();
		SignedJWT signedJwt = new SignedJWT(header, claims);
		try {
			signedJwt.sign(new RSASSASigner(rsaKey));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("JWT signing failed", ex);
		}
		return new AccessToken(signedJwt.serialize(), jti);
	}

	public RefreshTokenPair issueRefreshToken() {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		this.secureRandom.nextBytes(bytes);
		String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String hash = CryptoUtils.sha256Hex(plaintext);
		return new RefreshTokenPair(plaintext, hash);
	}

}
