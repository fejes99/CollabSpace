package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("INTERNAL_TOKEN_SSM_PATH")
public class InternalTokenSsmConfig {

	private final SsmConfigLoader ssm;

	private final String internalTokenSsmPath;

	public InternalTokenSsmConfig(SsmConfigLoader ssm,
			@Value("${INTERNAL_TOKEN_SSM_PATH:}") String internalTokenSsmPath) {
		this.ssm = ssm;
		this.internalTokenSsmPath = internalTokenSsmPath;
	}

	@Bean
	public InternalTokenProperties internalTokenProperties() {
		return new InternalTokenProperties(ssm.getParameter(internalTokenSsmPath));
	}

}
