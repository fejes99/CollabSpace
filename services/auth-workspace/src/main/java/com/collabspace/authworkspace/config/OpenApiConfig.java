package com.collabspace.authworkspace.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI().info(new Info().title("CollabSpace Auth API")
			.version("v1")
			.description("Authentication and workspace management service. "
					+ "Public endpoints require no token. Authenticated endpoints require a Bearer JWT "
					+ "validated by API Gateway before the request reaches this service."));
	}

}
