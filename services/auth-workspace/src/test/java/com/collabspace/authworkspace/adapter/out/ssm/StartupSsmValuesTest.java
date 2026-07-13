package com.collabspace.authworkspace.adapter.out.ssm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StartupSsmValues")
class StartupSsmValuesTest {

	private static final String PRIVATE_KEY_PATH = "/collabspace/dev/auth/jwt-private-key";

	private static final String ISSUER_PATH = "/collabspace/dev/jwt/issuer";

	private static final String AUDIENCE_PATH = "/collabspace/dev/jwt/audience";

	private static final String JWKS_URI_PATH = "/collabspace/dev/jwt/jwks-uri";

	private static final String INTERNAL_TOKEN_PATH = "/collabspace/dev/api/internal-token";

	@Mock
	private SsmConfigLoader ssm;

	@Test
	@DisplayName("fetches all five paths in one batch call and exposes each value")
	void fetchesAllPathsInOneBatchCall() {
		when(ssm
			.getParameters(List.of(PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH, INTERNAL_TOKEN_PATH)))
			.thenReturn(Map.of(PRIVATE_KEY_PATH, "pem", ISSUER_PATH, "issuer", AUDIENCE_PATH, "audience", JWKS_URI_PATH,
					"jwks-uri", INTERNAL_TOKEN_PATH, "token"));

		StartupSsmValues values = new StartupSsmValues(ssm, PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH,
				INTERNAL_TOKEN_PATH);

		assertThat(values.privateKey()).isEqualTo("pem");
		assertThat(values.issuer()).isEqualTo("issuer");
		assertThat(values.audience()).isEqualTo("audience");
		assertThat(values.jwksUri()).isEqualTo("jwks-uri");
		assertThat(values.internalToken()).isEqualTo("token");
		verify(ssm)
			.getParameters(List.of(PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH, INTERNAL_TOKEN_PATH));
	}

	@Test
	@DisplayName("excludes jwks-uri from the batch when its path is unset, and jwksUri() returns null")
	void excludesJwksUriWhenUnset() {
		when(ssm.getParameters(List.of(PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, INTERNAL_TOKEN_PATH)))
			.thenReturn(Map.of(PRIVATE_KEY_PATH, "pem", ISSUER_PATH, "issuer", AUDIENCE_PATH, "audience",
					INTERNAL_TOKEN_PATH, "token"));

		StartupSsmValues values = new StartupSsmValues(ssm, PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, "",
				INTERNAL_TOKEN_PATH);

		assertThat(values.jwksUri()).isNull();
	}

	@Test
	@DisplayName("excludes internal-token from the batch when its path is unset, and internalToken() returns null")
	void excludesInternalTokenWhenUnset() {
		when(ssm.getParameters(List.of(PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH))).thenReturn(Map
			.of(PRIVATE_KEY_PATH, "pem", ISSUER_PATH, "issuer", AUDIENCE_PATH, "audience", JWKS_URI_PATH, "jwks-uri"));

		StartupSsmValues values = new StartupSsmValues(ssm, PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH,
				"");

		assertThat(values.internalToken()).isNull();
	}

	@Test
	@DisplayName("throws when JWT_PRIVATE_KEY_SSM_PATH is blank")
	void throwsWhenPrivateKeyPathBlank() {
		assertThatThrownBy(
				() -> new StartupSsmValues(ssm, "", ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH, INTERNAL_TOKEN_PATH))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_PRIVATE_KEY_SSM_PATH");
	}

	@Test
	@DisplayName("throws when JWT_ISSUER_SSM_PATH is blank")
	void throwsWhenIssuerPathBlank() {
		assertThatThrownBy(() -> new StartupSsmValues(ssm, PRIVATE_KEY_PATH, "", AUDIENCE_PATH, JWKS_URI_PATH,
				INTERNAL_TOKEN_PATH))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_ISSUER_SSM_PATH");
	}

	@Test
	@DisplayName("throws when JWT_AUDIENCE_SSM_PATH is blank")
	void throwsWhenAudiencePathBlank() {
		assertThatThrownBy(
				() -> new StartupSsmValues(ssm, PRIVATE_KEY_PATH, ISSUER_PATH, "", JWKS_URI_PATH, INTERNAL_TOKEN_PATH))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_AUDIENCE_SSM_PATH");
	}

	@Test
	@DisplayName("propagates the missing-parameter failure from SsmConfigLoader")
	void propagatesMissingParameterFailure() {
		when(ssm
			.getParameters(List.of(PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH, INTERNAL_TOKEN_PATH)))
			.thenThrow(new IllegalStateException("Missing SSM parameters: [" + INTERNAL_TOKEN_PATH + "]"));

		assertThatThrownBy(() -> new StartupSsmValues(ssm, PRIVATE_KEY_PATH, ISSUER_PATH, AUDIENCE_PATH, JWKS_URI_PATH,
				INTERNAL_TOKEN_PATH))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(INTERNAL_TOKEN_PATH);
	}

}
