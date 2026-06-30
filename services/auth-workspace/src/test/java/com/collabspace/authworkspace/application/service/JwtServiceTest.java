package com.collabspace.authworkspace.application.service;

import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.model.auth.WorkspaceMembership;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

	private static RSAKey testKey;

	private JwtService jwtService;

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
		jwtService = new JwtService(testKey,
				new JwtProperties("https://test.issuer", "test-audience", "https://test/jwks"), Clock.systemUTC());
	}

	@Test
	void issueAccessTokenClaimsAreCorrect() throws Exception {
		List<WorkspaceMembership> memberships = List.of(new WorkspaceMembership("ws-1", "admin"));

		String token = jwtService.issueAccessToken("user-123", memberships);

		SignedJWT jwt = SignedJWT.parse(token);
		var claims = jwt.getJWTClaimsSet();
		assertThat(claims.getSubject()).isEqualTo("user:user-123");
		assertThat(claims.getStringClaim("userId")).isEqualTo("user-123");
		assertThat(claims.getIssuer()).isEqualTo("https://test.issuer");
		assertThat(claims.getAudience()).contains("test-audience");
		assertThat(claims.getJWTID()).isNotNull();
		assertThat(claims.getClaim("memberships")).isNotNull();
		long ttl = claims.getExpirationTime().toInstant().getEpochSecond()
				- claims.getIssueTime().toInstant().getEpochSecond();
		assertThat(ttl).isEqualTo(900);
	}

	@Test
	void issueRefreshTokenPlaintextDecodesToThirtyTwoBytes() {
		RefreshTokenPair pair = jwtService.issueRefreshToken();

		byte[] decoded = Base64.getUrlDecoder().decode(pair.plaintext());
		assertThat(decoded).hasSize(32);
	}

	@Test
	void issueRefreshTokenHashMatchesSha256OfPlaintext() {
		RefreshTokenPair pair = jwtService.issueRefreshToken();

		assertThat(pair.hash()).isEqualTo(CryptoUtils.sha256Hex(pair.plaintext()));
	}

	@Test
	void issueRefreshTokenTwoCallsProduceDifferentTokens() {
		RefreshTokenPair first = jwtService.issueRefreshToken();
		RefreshTokenPair second = jwtService.issueRefreshToken();

		assertThat(first.plaintext()).isNotEqualTo(second.plaintext());
	}

}
