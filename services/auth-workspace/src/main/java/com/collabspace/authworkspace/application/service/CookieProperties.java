package com.collabspace.authworkspace.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(boolean isSecure) {
}
