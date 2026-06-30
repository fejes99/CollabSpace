package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Configuration
@ConditionalOnProperty("JWT_PRIVATE_KEY")
public class LocalJwtConfig {

	private static final Logger log = LoggerFactory.getLogger(LocalJwtConfig.class);

	private final String privateKeyB64;

	private final String issuer;

	private final String audience;

	public LocalJwtConfig(@Value("${JWT_PRIVATE_KEY}") String privateKeyB64, @Value("${JWT_ISSUER}") String issuer,
			@Value("${JWT_AUDIENCE}") String audience) {
		this.privateKeyB64 = privateKeyB64;
		this.issuer = issuer;
		this.audience = audience;
	}

	@Bean
	public RSAKey rsaKey() throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {
		RSAPrivateKey privateKey = parsePrivateKey(privateKeyB64);
		RSAPublicKey publicKey = derivePublicKey(privateKey);
		RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
		log.info("Local JWT key loaded, kid={}", key.getKeyID());
		return key;
	}

	@Bean
	public JwtProperties jwtProperties() {
		return new JwtProperties(issuer, audience, "http://localhost:8080/.well-known/jwks.json");
	}

	private RSAPrivateKey parsePrivateKey(String b64) throws NoSuchAlgorithmException, InvalidKeySpecException {
		byte[] keyBytes = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
		return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
	}

	private RSAPublicKey derivePublicKey(RSAPrivateKey privateKey)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
		RSAPublicKeySpec spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
		return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
	}

}
