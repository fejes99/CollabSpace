package com.collabspace.authworkspace;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class JwtTestConfiguration {

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
