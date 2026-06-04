package com.collabspace.authworkspace.application.port.in.auth;

public record RegisterUserCommand(String name, String email, String password) {
}
