package com.collabspace.authworkspace.application.port.in.auth.command;

import java.util.Optional;

public record RefreshCommand(String token, Optional<String> userAgent, Optional<String> ipAddress) {
}
