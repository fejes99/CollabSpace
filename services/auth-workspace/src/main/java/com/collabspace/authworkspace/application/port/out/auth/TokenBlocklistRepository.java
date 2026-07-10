package com.collabspace.authworkspace.application.port.out.auth;

public interface TokenBlocklistRepository {

	boolean isBlocklisted(String jti);

}
