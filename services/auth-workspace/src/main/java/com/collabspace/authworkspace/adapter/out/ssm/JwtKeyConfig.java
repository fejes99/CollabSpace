package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@ConditionalOnProperty("JWT_PRIVATE_KEY_SSM_PATH")
public class JwtKeyConfig {

	private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

	private final StartupSsmValues values;

	public JwtKeyConfig(StartupSsmValues values) {
		this.values = values;
	}

	@Bean
	public RSAKey rsaKey() throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {
		RSAPrivateKey privateKey = parsePrivateKey(values.privateKey());
		RSAPublicKey publicKey = derivePublicKey(privateKey);
		RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
		log.info("JWT key loaded, kid={}", key.getKeyID());
		return key;
	}

	@Bean
	public JwtProperties jwtProperties() {
		String jwksUri = values.jwksUri() != null ? values.jwksUri() : "http://localhost:8080/.well-known/jwks.json";
		return new JwtProperties(values.issuer(), values.audience(), jwksUri);
	}

	private RSAPrivateKey parsePrivateKey(String pem) throws NoSuchAlgorithmException, InvalidKeySpecException {
		String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replaceAll("\\s", "");
		byte[] keyBytes = Base64.getDecoder().decode(encoded);
		return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
	}

	private RSAPublicKey derivePublicKey(RSAPrivateKey privateKey)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
		RSAPublicKeySpec spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
		return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
	}

}
