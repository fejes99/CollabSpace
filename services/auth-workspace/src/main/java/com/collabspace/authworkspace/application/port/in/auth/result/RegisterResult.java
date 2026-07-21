package com.collabspace.authworkspace.application.port.in.auth.result;

import com.collabspace.authworkspace.domain.model.auth.User;

public record RegisterResult(User user, String accessToken) {
}
