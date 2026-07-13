package com.collabspace.authworkspace.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	// Public: referenced from AuthController to scope register/login's Swagger
	// security requirement down to just this scheme -- see plan security-filter.md §6.
	public static final String INTERNAL_TOKEN_SCHEME = "X-Internal-Token";

	private static final String USER_ID_SCHEME = "X-User-Id";

	private static final String USER_WORKSPACES_SCHEME = "X-User-Workspaces";

	// No API Gateway locally, so these headers never arrive on their own -- Swagger's
	// "Authorize" button lets you set all three once instead of per request. See plan
	// security-filter.md §6.
	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info().title("CollabSpace Auth API")
				.version("v1")
				.description("Authentication and workspace management service. "
						+ "Public endpoints require no token. Authenticated endpoints require a Bearer JWT "
						+ "validated by API Gateway before the request reaches this service."))
			.components(new Components()
				.addSecuritySchemes(INTERNAL_TOKEN_SCHEME,
						apiKeyHeader(INTERNAL_TOKEN_SCHEME,
								"Shared secret normally injected by API Gateway. Required on every request, "
										+ "including register/login -- set this to your local INTERNAL_TOKEN value."))
				.addSecuritySchemes(USER_ID_SCHEME, apiKeyHeader(USER_ID_SCHEME,
						"JWT userId claim, normally forwarded by API Gateway's authorizer. Decode a JWT "
								+ "from /v1/auth/login's response and paste the userId claim here for protected routes."))
				.addSecuritySchemes(USER_WORKSPACES_SCHEME, apiKeyHeader(USER_WORKSPACES_SCHEME,
						"JWT memberships claim (JSON string), normally forwarded by API Gateway's authorizer. "
								+ "Decode a JWT from /v1/auth/login's response and paste the memberships claim here for protected routes.")))
			.addSecurityItem(new SecurityRequirement().addList(INTERNAL_TOKEN_SCHEME)
				.addList(USER_ID_SCHEME)
				.addList(USER_WORKSPACES_SCHEME));
	}

	private static SecurityScheme apiKeyHeader(String headerName, String description) {
		return new SecurityScheme().type(SecurityScheme.Type.APIKEY)
			.in(SecurityScheme.In.HEADER)
			.name(headerName)
			.description(description);
	}

}
