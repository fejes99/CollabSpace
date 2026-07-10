package com.collabspace.authworkspace.adapter.out.ssm;

import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("INTERNAL_TOKEN")
public class LocalInternalTokenConfig {

	private final String internalToken;

	public LocalInternalTokenConfig(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Bean
	public InternalTokenProperties internalTokenProperties() {
		// TODO: return new InternalTokenProperties(internalToken);
		throw new UnsupportedOperationException("TODO: wire InternalTokenProperties from local INTERNAL_TOKEN");
	}

}
