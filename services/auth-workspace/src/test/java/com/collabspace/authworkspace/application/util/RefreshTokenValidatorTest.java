package com.collabspace.authworkspace.application.util;

import com.collabspace.authworkspace.domain.exception.auth.InvalidTokenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RefreshTokenValidator")
class RefreshTokenValidatorTest {

	@Test
	@DisplayName("does not throw for a normal-length token")
	void doesNotThrowForNormalToken() {
		assertThatCode(() -> RefreshTokenValidator.validate("a-normal-looking-refresh-token-value"))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("throws InvalidTokenException when token is null")
	void throwsWhenTokenIsNull() {
		assertThatThrownBy(() -> RefreshTokenValidator.validate(null)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	@DisplayName("throws InvalidTokenException when token is empty")
	void throwsWhenTokenIsEmpty() {
		assertThatThrownBy(() -> RefreshTokenValidator.validate("")).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	@DisplayName("throws InvalidTokenException when token is blank (whitespace only)")
	void throwsWhenTokenIsBlank() {
		assertThatThrownBy(() -> RefreshTokenValidator.validate("   ")).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	@DisplayName("does not throw for a token exactly at the 256-byte boundary")
	void doesNotThrowAtExactly256Bytes() {
		String token = "a".repeat(256);

		assertThatCode(() -> RefreshTokenValidator.validate(token)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("throws InvalidTokenException for a token one byte over the 256-byte limit")
	void throwsOneByteOverLimit() {
		String token = "a".repeat(257);

		assertThatThrownBy(() -> RefreshTokenValidator.validate(token)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	@DisplayName("measures length in UTF-8 bytes, not characters")
	void measuresUtf8BytesNotCharacters() {
		// Each 'é' is 2 bytes in UTF-8 -- 129 of them is 258 bytes, over the limit, even
		// though the character count (129) alone would be well under it.
		String token = "é".repeat(129);

		assertThatThrownBy(() -> RefreshTokenValidator.validate(token)).isInstanceOf(InvalidTokenException.class);
	}

}
