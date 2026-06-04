package com.collabspace.authworkspace.application.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CryptoUtils {

	private CryptoUtils() {
	}

	public static String sha256Hex(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
