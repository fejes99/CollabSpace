package com.collabspace.authworkspace.application.service.auth;

import com.collabspace.authworkspace.application.port.in.auth.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.RegisterResult;
import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.service.RefreshTokenPair;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.exception.InvalidCredentialsException;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
import com.collabspace.authworkspace.domain.model.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

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
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private PasswordEncoder passwordEncoder;

	private AuthApplicationService service;

	@BeforeEach
	void setup() {
		service = new AuthApplicationService(userRepository, refreshTokenRepository, jwtService, passwordEncoder,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("returns user and access token for a valid register command")
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
	@DisplayName("register normalises email to lowercase before persisting")
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
	@DisplayName("register throws EmailAlreadyTakenException when repository rejects duplicate email")
	void registerDuplicateEmailThrowsWhenRepositoryRejects() {
		RegisterCommand command = new RegisterCommand("Alice", "alice@example.com", "password123");
		when(passwordEncoder.encode("password123")).thenReturn("hashed_pw");
		when(userRepository.save(any(User.class))).thenThrow(new EmailAlreadyTakenException());

		assertThrows(EmailAlreadyTakenException.class, () -> service.register(command));
	}

	@Test
	@DisplayName("returns access token, refresh token, and user for valid login credentials")
	void loginWithValidCredentialsReturnsAccessAndRefreshTokenAndUser() {
		LoginCommand command = new LoginCommand("alice@example.com", "password123", Optional.empty(), Optional.empty());
		User existingUser = new User(UUID.randomUUID(), "Alice", "alice@example.com", Optional.of("hashed_pw"),
				FIXED_INSTANT, FIXED_INSTANT);

		when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existingUser));
		when(passwordEncoder.matches("password123", "hashed_pw")).thenReturn(true);
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn("jwt.token");
		when(jwtService.issueRefreshToken()).thenReturn(new RefreshTokenPair("refresh_plaintext", "refresh_hash"));
		when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

		LoginResult result = service.login(command);

		assertThat(result.accessToken()).isEqualTo("jwt.token");
		assertThat(result.refreshToken()).isEqualTo("refresh_plaintext");
		assertThat(result.user().name()).isEqualTo("Alice");
		assertThat(result.user().email()).isEqualTo("alice@example.com");
		assertThat(result.user().createdAt()).isEqualTo(FIXED_INSTANT);
		assertThat(result.user().updatedAt()).isEqualTo(FIXED_INSTANT);

		ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(tokenCaptor.capture());
		assertThat(tokenCaptor.getValue().expiresAt()).isEqualTo(FIXED_INSTANT.plusSeconds(604800));
	}

	@Test
	@DisplayName("login normalises email to lowercase before looking up user")
	void loginEmailIsNormalisedBeforeLookup() {
		LoginCommand command = new LoginCommand("Alice@EXAMPLE.COM", "password123", Optional.empty(), Optional.empty());
		User existingUser = new User(UUID.randomUUID(), "Alice", "alice@example.com", Optional.of("hashed_pw"),
				FIXED_INSTANT, FIXED_INSTANT);

		when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existingUser));
		when(passwordEncoder.matches("password123", "hashed_pw")).thenReturn(true);
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn("jwt.token");
		when(jwtService.issueRefreshToken()).thenReturn(new RefreshTokenPair("plaintext", "hash"));
		when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

		service.login(command);

		verify(userRepository).findByEmail("alice@example.com");
	}

	@Test
	@DisplayName("login throws InvalidCredentialsException when email is not registered")
	void loginUnknownEmailThrowsInvalidCredentials() {
		LoginCommand command = new LoginCommand("unknown@example.com", "password123", Optional.empty(),
				Optional.empty());
		when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

		assertThrows(InvalidCredentialsException.class, () -> service.login(command));
	}

	@Test
	@DisplayName("login throws InvalidCredentialsException when account has no password hash (social login user)")
	void loginAccountWithNoPasswordHashThrowsInvalidCredentials() {
		LoginCommand command = new LoginCommand("alice@example.com", "password123", Optional.empty(), Optional.empty());
		User existingUser = new User(UUID.randomUUID(), "Alice", "alice@example.com", Optional.empty(), FIXED_INSTANT,
				FIXED_INSTANT);
		when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existingUser));

		assertThrows(InvalidCredentialsException.class, () -> service.login(command));
	}

	@Test
	@DisplayName("login throws InvalidCredentialsException when password does not match stored hash")
	void loginWrongPasswordThrowsInvalidCredentials() {
		LoginCommand command = new LoginCommand("alice@example.com", "wrongpassword", Optional.empty(),
				Optional.empty());
		User existingUser = new User(UUID.randomUUID(), "Alice", "alice@example.com", Optional.of("hashed_pw"),
				FIXED_INSTANT, FIXED_INSTANT);

		when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existingUser));
		when(passwordEncoder.matches("wrongpassword", "hashed_pw")).thenReturn(false);

		assertThrows(InvalidCredentialsException.class, () -> service.login(command));
	}

}
