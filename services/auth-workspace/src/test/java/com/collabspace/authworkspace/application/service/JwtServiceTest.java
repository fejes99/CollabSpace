package com.collabspace.authworkspace.application.service;

import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtService")
class JwtServiceTest {

	private static final UUID TEST_USER_ID = UUID.randomUUID();

	private static RSAKey testKey;

	private JwtService jwtService;

	private ObjectMapper objectMapper;

	@BeforeAll
	static void generateKey() throws Exception {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
		gen.initialize(2048);
		KeyPair pair = gen.generateKeyPair();
		testKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
			.keyIDFromThumbprint()
			.build();
	}

	@BeforeEach
	void setup() {
		objectMapper = new ObjectMapper();
		jwtService = new JwtService(testKey,
				new JwtProperties("https://test.issuer", "test-audience", "https://test/jwks"), Clock.systemUTC(),
				objectMapper);
	}

	@Test
	@DisplayName("access token contains correct claims and 15-minute TTL")
	void issueAccessTokenClaimsAreCorrect() throws Exception {
		UUID workspaceId = UUID.randomUUID();
		Instant now = Instant.now();
		List<WorkspaceMembership> memberships = List.of(new WorkspaceMembership(UUID.randomUUID(), workspaceId,
				UUID.randomUUID(), WorkspaceRole.ADMIN, now, now));

		AccessToken accessToken = jwtService.issueAccessToken(TEST_USER_ID, memberships);

		SignedJWT jwt = SignedJWT.parse(accessToken.token());
		var claims = jwt.getJWTClaimsSet();
		assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
		assertThat(claims.getSubject()).isEqualTo("user:" + TEST_USER_ID);
		assertThat(claims.getStringClaim("userId")).isEqualTo(TEST_USER_ID.toString());
		assertThat(claims.getIssuer()).isEqualTo("https://test.issuer");
		assertThat(claims.getAudience()).contains("test-audience");
		assertThat(claims.getJWTID()).isEqualTo(accessToken.jti());
		long ttl = claims.getExpirationTime().toInstant().getEpochSecond()
				- claims.getIssueTime().toInstant().getEpochSecond();
		assertThat(ttl).isEqualTo(900);

		String membershipsClaim = claims.getStringClaim("memberships");
		List<Map<String, Object>> membershipsInToken = objectMapper.readValue(membershipsClaim, new TypeReference<>() {
		});
		assertThat(membershipsInToken).hasSize(1);
		assertThat(membershipsInToken.get(0)).containsEntry("workspaceId", workspaceId.toString())
			.containsEntry("role", "admin");
	}

	@Test
	@DisplayName("access token signature is verifiable with the RSA public key")
	void issueAccessTokenSignatureIsVerifiableWithPublicKey() throws Exception {
		AccessToken accessToken = jwtService.issueAccessToken(TEST_USER_ID, List.of());

		SignedJWT jwt = SignedJWT.parse(accessToken.token());

		assertThat(jwt.verify(new RSASSAVerifier(testKey.toPublicJWK()))).isTrue();
	}

	@Test
	@DisplayName("refresh token plaintext decodes to 32 bytes")
	void issueRefreshTokenPlaintextDecodesToThirtyTwoBytes() {
		RefreshTokenPair pair = jwtService.issueRefreshToken();

		byte[] decoded = Base64.getUrlDecoder().decode(pair.plaintext());
		assertThat(decoded).hasSize(32);
	}

	@Test
	@DisplayName("refresh token hash is SHA-256 of plaintext")
	void issueRefreshTokenHashMatchesSha256OfPlaintext() {
		RefreshTokenPair pair = jwtService.issueRefreshToken();

		assertThat(pair.hash()).isEqualTo(CryptoUtils.sha256Hex(pair.plaintext()));
	}

	@Test
	@DisplayName("two refresh tokens are always distinct")
	void issueRefreshTokenTwoCallsProduceDifferentTokens() {
		RefreshTokenPair first = jwtService.issueRefreshToken();
		RefreshTokenPair second = jwtService.issueRefreshToken();

		assertThat(first.plaintext()).isNotEqualTo(second.plaintext());
	}

}
