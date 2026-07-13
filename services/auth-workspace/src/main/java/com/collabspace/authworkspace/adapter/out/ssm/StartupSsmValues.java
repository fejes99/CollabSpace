package com.collabspace.authworkspace.adapter.out.ssm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Single point where JwtKeyConfig's and InternalTokenSsmConfig's SSM values are
// fetched -- one ssm:GetParameters call instead of five sequential ssm:GetParameter
// calls on the startup path. See plan batch-startup-ssm-fetch.md.
//
// Gated on JWT_PRIVATE_KEY_SSM_PATH like SsmConfigLoader itself: in AWS dev, all five
// paths are always set together (same Terraform block), so this couples
// InternalTokenSsmConfig's activation to the JWT path being set too. If INTERNAL_TOKEN_SSM_PATH
// is unset, its value is simply excluded from the batch and InternalTokenSsmConfig
// (independently gated on INTERNAL_TOKEN_SSM_PATH) never gets constructed.
@Component
@ConditionalOnProperty("JWT_PRIVATE_KEY_SSM_PATH")
public class StartupSsmValues {

	private static final Logger log = LoggerFactory.getLogger(StartupSsmValues.class);

	private final String privateKeySsmPath;

	private final String issuerSsmPath;

	private final String audienceSsmPath;

	private final String jwksUriSsmPath;

	private final String internalTokenSsmPath;

	private final Map<String, String> values;

	public StartupSsmValues(SsmConfigLoader ssm, @Value("${JWT_PRIVATE_KEY_SSM_PATH:}") String privateKeySsmPath,
			@Value("${JWT_ISSUER_SSM_PATH:}") String issuerSsmPath,
			@Value("${JWT_AUDIENCE_SSM_PATH:}") String audienceSsmPath,
			@Value("${JWT_JWKS_URI_SSM_PATH:}") String jwksUriSsmPath,
			@Value("${INTERNAL_TOKEN_SSM_PATH:}") String internalTokenSsmPath) {
		requireText(privateKeySsmPath, "JWT_PRIVATE_KEY_SSM_PATH");
		requireText(issuerSsmPath, "JWT_ISSUER_SSM_PATH");
		requireText(audienceSsmPath, "JWT_AUDIENCE_SSM_PATH");

		this.privateKeySsmPath = privateKeySsmPath;
		this.issuerSsmPath = issuerSsmPath;
		this.audienceSsmPath = audienceSsmPath;
		this.jwksUriSsmPath = jwksUriSsmPath;
		this.internalTokenSsmPath = internalTokenSsmPath;

		List<String> paths = new ArrayList<>(List.of(privateKeySsmPath, issuerSsmPath, audienceSsmPath));
		if (StringUtils.hasText(jwksUriSsmPath)) {
			paths.add(jwksUriSsmPath);
		}
		if (StringUtils.hasText(internalTokenSsmPath)) {
			paths.add(internalTokenSsmPath);
		}

		this.values = ssm.getParameters(paths);
		log.info("event=ssm_batch_fetch_completed paths={}", paths.size());
	}

	public String privateKey() {
		return values.get(privateKeySsmPath);
	}

	public String issuer() {
		return values.get(issuerSsmPath);
	}

	public String audience() {
		return values.get(audienceSsmPath);
	}

	public String jwksUri() {
		return StringUtils.hasText(jwksUriSsmPath) ? values.get(jwksUriSsmPath) : null;
	}

	public String internalToken() {
		return values.get(internalTokenSsmPath);
	}

	private void requireText(String value, String propertyName) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(propertyName + " is not configured");
		}
	}

}
