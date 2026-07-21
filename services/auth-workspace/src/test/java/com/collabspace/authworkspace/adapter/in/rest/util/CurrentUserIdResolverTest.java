package com.collabspace.authworkspace.adapter.in.rest.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CurrentUserIdResolver")
class CurrentUserIdResolverTest {

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("returns the UUID parsed from the Authentication principal name")
	void returnsUserIdFromAuthenticationName() {
		UUID userId = UUID.randomUUID();
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(userId.toString(), null));

		assertThat(CurrentUserIdResolver.resolve()).isEqualTo(userId);
	}

	@Test
	@DisplayName("throws IllegalStateException when no Authentication is present in the SecurityContext")
	void throwsIllegalStateExceptionWhenAuthenticationMissing() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(CurrentUserIdResolver::resolve).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no Authentication in the SecurityContext");
	}

}
