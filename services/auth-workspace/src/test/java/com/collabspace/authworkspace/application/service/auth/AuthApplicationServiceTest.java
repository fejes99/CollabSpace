package com.collabspace.authworkspace.application.service.auth;

import com.collabspace.authworkspace.application.port.in.auth.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.RegisterResult;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.model.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthApplicationService")
class AuthApplicationServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-06-04T10:00:00Z");

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private PasswordEncoder passwordEncoder;

	private AuthApplicationService service;

	@BeforeEach
	void setup() {
		service = new AuthApplicationService(userRepository, jwtService, passwordEncoder,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("returns user and access token for a valid command")
	void registerValidCommandReturnsUserAndToken() {
		RegisterCommand command = new RegisterCommand("Alice", "alice@example.com", "password123");
		when(passwordEncoder.encode("password123")).thenReturn("hashed_pw");
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn("jwt.token");

		RegisterResult result = service.register(command);

		assertThat(result.accessToken()).isEqualTo("jwt.token");
		assertThat(result.user().name()).isEqualTo("Alice");
		assertThat(result.user().email()).isEqualTo("alice@example.com");
		assertThat(result.user().createdAt()).isEqualTo(FIXED_INSTANT);
		assertThat(result.user().updatedAt()).isEqualTo(FIXED_INSTANT);
		assertThat(result.user().passwordHash()).hasValue("hashed_pw");
	}

	@Test
	@DisplayName("normalises email to lowercase before persisting")
	void registerEmailIsNormalisedStoredAsLowercase() {
		RegisterCommand command = new RegisterCommand("Alice", "Alice@EXAMPLE.COM", "password123");
		when(passwordEncoder.encode(anyString())).thenReturn("hashed");
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn("token");

		RegisterResult result = service.register(command);

		verify(userRepository).save(argThat(u -> "alice@example.com".equals(u.email())));
		assertThat(result.user().email()).isEqualTo("alice@example.com");
	}

	@Test
	@DisplayName("throws EmailAlreadyTakenException when repository rejects duplicate email")
	void registerDuplicateEmailThrowsWhenRepositoryRejects() {
		RegisterCommand command = new RegisterCommand("Alice", "alice@example.com", "password123");
		when(passwordEncoder.encode("password123")).thenReturn("hashed_pw");
		when(userRepository.save(any(User.class))).thenThrow(new EmailAlreadyTakenException());

		assertThrows(EmailAlreadyTakenException.class, () -> service.register(command));
	}

}
