package com.collabspace.authworkspace.adapter.in.rest;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest(WellKnownController.class)
@Import({ SecurityConfig.class, WellKnownControllerTest.TestConfig.class })
class WellKnownControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void jwks_returnsPublicKeyOnly() throws Exception {
		mockMvc.perform(get("/.well-known/jwks.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keys").isArray())
			.andExpect(jsonPath("$.keys[0].kty").value("RSA"))
			.andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
			.andExpect(jsonPath("$.keys[0].d").doesNotExist());
	}

	@Test
	void oidcDiscovery_returnsRequiredFields() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
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

	}

}
