package com.collabspace.authworkspace.application.port.in.auth.command;

import java.util.Optional;

public record LoginCommand(String email, String password, Optional<String> userAgent, Optional<String> ipAddress) {
}
