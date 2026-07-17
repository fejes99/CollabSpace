package com.collabspace.authworkspace.adapter.in.rest.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

	@Mock
	private HttpServletRequest request;

	@Test
	@DisplayName("returns the remote address when X-Forwarded-For is absent")
	void returnsRemoteAddrWhenHeaderAbsent() {
		when(request.getHeader("X-Forwarded-For")).thenReturn(null);
		when(request.getRemoteAddr()).thenReturn("198.51.100.7");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.7");
	}

	@Test
	@DisplayName("returns the first hop of X-Forwarded-For when present")
	void returnsFirstHopWhenHeaderPresent() {
		when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 198.51.100.1, 10.0.0.1");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.5");
	}

	@Test
	@DisplayName("trims whitespace from the first hop")
	void trimsWhitespaceFromFirstHop() {
		when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.5  ,198.51.100.1");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.5");
	}

	@Test
	@DisplayName("falls back to the remote address when X-Forwarded-For is blank")
	void fallsBackToRemoteAddrWhenHeaderBlank() {
		when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
		when(request.getRemoteAddr()).thenReturn("198.51.100.7");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.7");
	}

	@Test
	@DisplayName("falls back to the remote address when X-Forwarded-For's first hop is blank")
	void fallsBackToRemoteAddrWhenFirstHopBlank() {
		when(request.getHeader("X-Forwarded-For")).thenReturn(" ,198.51.100.1");
		when(request.getRemoteAddr()).thenReturn("198.51.100.7");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.7");
	}

}
