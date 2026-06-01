package com.collabspace.authworkspace.application.service;

public record JwtProperties(String issuer, String audience, String jwksUri) {
}
