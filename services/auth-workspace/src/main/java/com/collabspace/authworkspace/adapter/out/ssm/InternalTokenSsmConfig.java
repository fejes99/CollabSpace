package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("INTERNAL_TOKEN_SSM_PATH")
public class InternalTokenSsmConfig {

	private final StartupSsmValues values;

	public InternalTokenSsmConfig(StartupSsmValues values) {
		this.values = values;
	}

	@Bean
	public InternalTokenProperties internalTokenProperties() {
		return new InternalTokenProperties(values.internalToken());
	}

}
