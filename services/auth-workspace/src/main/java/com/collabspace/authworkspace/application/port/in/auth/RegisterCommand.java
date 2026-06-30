package com.collabspace.authworkspace.application.port.in.auth;

public record RegisterCommand(String name, String email, String password) {
}
