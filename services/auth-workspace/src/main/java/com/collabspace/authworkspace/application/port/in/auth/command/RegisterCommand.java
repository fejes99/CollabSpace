package com.collabspace.authworkspace.application.port.in.auth.command;

import java.util.Optional;

public record RegisterCommand(String name, String email, String password, Optional<String> ipAddress) {
}
