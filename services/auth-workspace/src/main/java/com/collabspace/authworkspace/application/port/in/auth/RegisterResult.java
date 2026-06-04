package com.collabspace.authworkspace.application.port.in.auth;

import com.collabspace.authworkspace.domain.model.auth.User;

public record RegisterResult(User user, String accessToken) {
}
