package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class JwtKeyConfig {

	private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

	private final SsmConfigLoader ssm;

	private final String privateKeySsmPath;

	private final String issuerSsmPath;

	private final String audienceSsmPath;

	private final String jwksUriSsmPath;

	public JwtKeyConfig(SsmConfigLoader ssm, @Value("${JWT_PRIVATE_KEY_SSM_PATH:}") String privateKeySsmPath,
			@Value("${JWT_ISSUER_SSM_PATH:}") String issuerSsmPath,
			@Value("${JWT_AUDIENCE_SSM_PATH:}") String audienceSsmPath,
			@Value("${JWT_JWKS_URI_SSM_PATH:}") String jwksUriSsmPath) {
		this.ssm = ssm;
		this.privateKeySsmPath = privateKeySsmPath;
		this.issuerSsmPath = issuerSsmPath;
		this.audienceSsmPath = audienceSsmPath;
		this.jwksUriSsmPath = jwksUriSsmPath;
	}

	@Bean
	@ConditionalOnMissingBean
	public RSAKey rsaKey() throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {
		if (!StringUtils.hasText(this.privateKeySsmPath)) {
			throw new IllegalStateException("JWT_PRIVATE_KEY_SSM_PATH is not configured");
		}
		String pem = this.ssm.getParameter(this.privateKeySsmPath);
		RSAPrivateKey privateKey = parsePrivateKey(pem);
		RSAPublicKey publicKey = derivePublicKey(privateKey);
		RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
		log.info("JWT key loaded, kid={}", key.getKeyID());
		return key;
	}

	@Bean
	@ConditionalOnMissingBean
	public JwtProperties jwtProperties() {
		String jwksUri = StringUtils.hasText(this.jwksUriSsmPath) ? this.ssm.getParameter(this.jwksUriSsmPath)
				: "http://localhost:8080/.well-known/jwks.json";
		return new JwtProperties(this.ssm.getParameter(this.issuerSsmPath), this.ssm.getParameter(this.audienceSsmPath),
				jwksUri);
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
