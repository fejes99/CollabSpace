package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.SecurityConfig;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.HeaderAuthenticationFilter;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.InternalTokenFilter;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.JwtBlocklistFilter;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.MembershipStalenessFilter;
import com.collabspace.authworkspace.adapter.in.rest.wellknown.WellKnownController;
import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest(WellKnownController.class)
@Import({ SecurityConfig.class, InternalTokenFilter.class, HeaderAuthenticationFilter.class, JwtBlocklistFilter.class,
		MembershipStalenessFilter.class, ProblemDetailsSecurityHandler.class,
		WellKnownControllerTest.TestConfig.class })
@DisplayName("GET /.well-known")
class WellKnownControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("returns RSA public key without private key material")
	void jwksReturnsPublicKeyOnly() throws Exception {
		mvc.perform(get("/.well-known/jwks.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keys").isArray())
			.andExpect(jsonPath("$.keys[0].kty").value("RSA"))
			.andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
			.andExpect(jsonPath("$.keys[0].n").isNotEmpty())
			.andExpect(jsonPath("$.keys[0].e").isNotEmpty())
			.andExpect(jsonPath("$.keys[0].d").doesNotExist())
			.andExpect(jsonPath("$.keys[0].p").doesNotExist())
			.andExpect(jsonPath("$.keys[0].q").doesNotExist())
			.andExpect(jsonPath("$.keys[0].dp").doesNotExist())
			.andExpect(jsonPath("$.keys[0].dq").doesNotExist())
			.andExpect(jsonPath("$.keys[0].qi").doesNotExist());
	}

	@Test
	@DisplayName("OIDC discovery returns issuer, JWKS URI, and signing algorithm")
	void oidcDiscoveryReturnsRequiredFields() throws Exception {
		mvc.perform(get("/.well-known/openid-configuration"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issuer").value("https://test.issuer"))
			.andExpect(jsonPath("$.jwks_uri").value("https://test/jwks"))
			.andExpect(jsonPath("$.id_token_signing_alg_values_supported[0]").value("RS256"));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		RSAKey rsaKey() throws Exception {
			KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
			gen.initialize(2048);
			KeyPair pair = gen.generateKeyPair();
			return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
				.keyIDFromThumbprint()
				.build();
		}

		@Bean
		JwtProperties jwtProperties() {
			return new JwtProperties("https://test.issuer", "test-audience", "https://test/jwks");
		}

		@Bean
		InternalTokenProperties internalTokenProperties() {
			return new InternalTokenProperties("test-internal-token");
		}

		@Bean
		TokenBlocklistRepository tokenBlocklistRepository() {
			return jti -> false;
		}

		@Bean
		MembershipStalenessRepository membershipStalenessRepository() {
			return new MembershipStalenessRepository() {
				@Override
				public void markMembershipChanged(UUID userId, Instant changedAt) {
					// no-op: this test class doesn't exercise membership staleness, just
					// satisfies MembershipStalenessFilter's constructor dependency.
				}

				@Override
				public Optional<Instant> findMembershipChangedAt(UUID userId) {
					return Optional.empty();
				}
			};
		}

	}

}
