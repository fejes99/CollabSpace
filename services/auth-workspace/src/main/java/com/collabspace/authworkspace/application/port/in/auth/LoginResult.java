package com.collabspace.authworkspace.application.port.in.auth;

import com.collabspace.authworkspace.domain.model.auth.User;

public record LoginResult(User user, String accessToken) {
}
