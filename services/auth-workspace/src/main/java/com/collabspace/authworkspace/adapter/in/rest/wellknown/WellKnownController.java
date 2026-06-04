package com.collabspace.authworkspace.adapter.in.rest.wellknown;

import com.collabspace.authworkspace.application.service.JwtProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Hidden
@RestController
public class WellKnownController {

	private final RSAKey rsaKey;

	private final JwtProperties jwtProperties;

	public WellKnownController(RSAKey rsaKey, JwtProperties jwtProperties) {
		this.rsaKey = rsaKey;
		this.jwtProperties = jwtProperties;
	}

	@GetMapping("/.well-known/jwks.json")
	public ResponseEntity<String> getJwks() {
		String jwks = new JWKSet(this.rsaKey.toPublicJWK()).toString();
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(jwks);
	}

	@GetMapping("/.well-known/openid-configuration")
	public ResponseEntity<Map<String, Object>> getOidcConfiguration() {
		Map<String, Object> document = Map.of("issuer", jwtProperties.issuer(), "jwks_uri", jwtProperties.jwksUri(),
				"id_token_signing_alg_values_supported", List.of("RS256"));
		return ResponseEntity.ok(document);
	}

}
