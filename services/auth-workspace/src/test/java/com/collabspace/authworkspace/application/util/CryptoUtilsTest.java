package com.collabspace.authworkspace.application.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CryptoUtils")
class CryptoUtilsTest {

	@Test
	@DisplayName("sha256Hex matches a known SHA-256 test vector")
	void sha256HexMatchesKnownVector() {
		// echo -n "hello" | sha256sum
		assertThat(CryptoUtils.sha256Hex("hello"))
			.isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
	}

	@Test
	@DisplayName("sha256Hex is deterministic for the same input")
	void sha256HexIsDeterministic() {
		assertThat(CryptoUtils.sha256Hex("alice@example.com")).isEqualTo(CryptoUtils.sha256Hex("alice@example.com"));
	}

	@Test
	@DisplayName("sha256Hex produces different output for different input")
	void sha256HexDiffersForDifferentInput() {
		assertThat(CryptoUtils.sha256Hex("alice@example.com")).isNotEqualTo(CryptoUtils.sha256Hex("bob@example.com"));
	}

	@Test
	@DisplayName("sha256Hex never returns the plaintext input")
	void sha256HexNeverReturnsPlaintext() {
		String email = "alice@example.com";

		assertThat(CryptoUtils.sha256Hex(email)).doesNotContain(email);
	}

	@Test
	@DisplayName("sha256Hex output is lowercase hex, 64 characters")
	void sha256HexOutputIsLowercaseHex64Chars() {
		String hash = CryptoUtils.sha256Hex("test");

		assertThat(hash).hasSize(64);
		assertThat(hash).matches("[0-9a-f]{64}");
	}

}
