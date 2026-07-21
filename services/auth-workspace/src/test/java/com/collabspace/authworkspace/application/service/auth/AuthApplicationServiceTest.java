package com.collabspace.authworkspace.application.service.auth;

import com.collabspace.authworkspace.application.port.in.auth.command.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RefreshCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.result.RefreshResult;
import com.collabspace.authworkspace.application.port.in.auth.result.RegisterResult;
import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.service.RefreshTokenPair;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.exception.auth.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.exception.auth.ExpiredRefreshTokenException;
import com.collabspace.authworkspace.domain.exception.auth.InvalidCredentialsException;
import com.collabspace.authworkspace.domain.exception.auth.InvalidTokenException;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
import com.collabspace.authworkspace.domain.model.auth.User;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthApplicationService")
class AuthApplicationServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-06-04T10:00:00Z");

	private static final String TEST_IP = "192.0.2.1";

	@Mock
	private UserRepository userRepository;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private WorkspaceMembershipRepository workspaceMembershipRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private PasswordEncoder passwordEncoder;

	private AuthApplicationService service;

	@BeforeEach
	void setup() {
		service = new AuthApplicationService(userRepository, refreshTokenRepository, workspaceMembershipRepository,
				jwtService, passwordEncoder, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("returns user and access token for a valid register command")
	void registerValidCommandReturnsUserAndToken() {
		RegisterCommand command = new RegisterCommand("Alice", "alice@example.com", "password123",
				Optional.of(TEST_IP));
		when(passwordEncoder.encode("password123")).thenReturn("hashed_pw");
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(any(UUID.class), anyList()))
			.thenReturn(new AccessToken("jwt.token", "test-jti"));

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
		RegisterCommand command = new RegisterCommand("Alice", "Alice@EXAMPLE.COM", "password123",
				Optional.of(TEST_IP));
		when(passwordEncoder.encode(anyString())).thenReturn("hashed");
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(any(UUID.class), anyList())).thenReturn(new AccessToken("token", "test-jti"));

		RegisterResult result = service.register(command);

		verify(userRepository).save(argThat(u -> "alice@example.com".equals(u.email())));
		assertThat(result.user().email()).isEqualTo("alice@example.com");
	}

	@Test
	@DisplayName("register throws EmailAlreadyTakenException when repository rejects duplicate email")
	void registerDuplicateEmailThrowsWhenRepositoryRejects() {
		RegisterCommand command = new RegisterCommand("Alice", "alice@example.com", "password123",
				Optional.of(TEST_IP));
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
		when(jwtService.issueAccessToken(any(UUID.class), anyList()))
			.thenReturn(new AccessToken("jwt.token", "test-jti"));
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
		when(jwtService.issueAccessToken(any(UUID.class), anyList()))
			.thenReturn(new AccessToken("jwt.token", "test-jti"));
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

	@Test
	@DisplayName("refresh rotates the token, re-derives current memberships, and returns a new access and refresh token")
	void refreshWithValidTokenReturnsNewAccessTokenAndRotatesRefreshToken() {
		String rawToken = "raw-refresh-token-value";
		String hashedToken = CryptoUtils.sha256Hex(rawToken);
		UUID existingUserId = UUID.randomUUID();
		RefreshToken existingToken = new RefreshToken(UUID.randomUUID(), existingUserId, hashedToken,
				FIXED_INSTANT.minusSeconds(100), FIXED_INSTANT.plusSeconds(604700), Optional.of("old-agent"),
				Optional.of("10.0.0.1"));
		RefreshCommand command = new RefreshCommand(rawToken, Optional.of("new-agent"), Optional.of(TEST_IP));
		List<WorkspaceMembership> memberships = List.of(new WorkspaceMembership(UUID.randomUUID(), UUID.randomUUID(),
				existingUserId, WorkspaceRole.ADMIN, FIXED_INSTANT, FIXED_INSTANT));

		when(refreshTokenRepository.findByTokenHash(hashedToken)).thenReturn(Optional.of(existingToken));
		when(refreshTokenRepository.deleteByIdReturningCount(existingToken.id())).thenReturn(1);
		when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueRefreshToken()).thenReturn(new RefreshTokenPair("new-plaintext", "new-hash"));
		when(workspaceMembershipRepository.findByUserId(existingUserId)).thenReturn(memberships);
		when(jwtService.issueAccessToken(existingUserId, memberships))
			.thenReturn(new AccessToken("new-access-token", "new-jti"));

		RefreshResult result = service.refresh(command);

		assertThat(result.accessToken()).isEqualTo("new-access-token");
		assertThat(result.refreshToken()).isEqualTo("new-plaintext");

		verify(refreshTokenRepository).deleteByIdReturningCount(existingToken.id());

		ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(savedCaptor.capture());
		RefreshToken saved = savedCaptor.getValue();
		assertThat(saved.userId()).isEqualTo(existingUserId);
		assertThat(saved.tokenHash()).isEqualTo("new-hash");
		assertThat(saved.expiresAt()).isEqualTo(FIXED_INSTANT.plusSeconds(604800));
		// Captures the CURRENT request's userAgent/ipAddress, not the old row's -- a
		// refresh from a different device must not leave the audit trail pointing at
		// whichever device logged in originally.
		assertThat(saved.userAgent()).hasValue("new-agent");
		assertThat(saved.ipAddress()).hasValue(TEST_IP);
	}

	@Test
	@DisplayName("refresh throws InvalidTokenException when the token hash matches no row")
	void refreshUnknownTokenThrowsInvalidTokenException() {
		RefreshCommand command = new RefreshCommand("garbage-token", Optional.empty(), Optional.of(TEST_IP));
		when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

		assertThrows(InvalidTokenException.class, () -> service.refresh(command));

		verify(refreshTokenRepository, never()).deleteByIdReturningCount(any());
		verify(refreshTokenRepository, never()).save(any());
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("refresh throws ExpiredRefreshTokenException and deletes the row when the token has expired")
	void refreshExpiredTokenThrowsExpiredRefreshTokenExceptionAndDeletesRow() {
		String rawToken = "expired-token-value";
		String hashedToken = CryptoUtils.sha256Hex(rawToken);
		RefreshToken expiredToken = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), hashedToken,
				FIXED_INSTANT.minusSeconds(700000), FIXED_INSTANT.minusSeconds(1), Optional.empty(), Optional.empty());
		RefreshCommand command = new RefreshCommand(rawToken, Optional.empty(), Optional.of(TEST_IP));
		when(refreshTokenRepository.findByTokenHash(hashedToken)).thenReturn(Optional.of(expiredToken));

		assertThrows(ExpiredRefreshTokenException.class, () -> service.refresh(command));

		verify(refreshTokenRepository).deleteByIdReturningCount(expiredToken.id());
		verify(refreshTokenRepository, never()).save(any());
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("refresh throws InvalidTokenException when it loses the rotation race to a concurrent refresh")
	void refreshLosesRaceWhenTokenAlreadyRotatedThrowsInvalidTokenException() {
		String rawToken = "raced-token-value";
		String hashedToken = CryptoUtils.sha256Hex(rawToken);
		RefreshToken existingToken = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), hashedToken,
				FIXED_INSTANT.minusSeconds(100), FIXED_INSTANT.plusSeconds(604700), Optional.empty(), Optional.empty());
		RefreshCommand command = new RefreshCommand(rawToken, Optional.empty(), Optional.of(TEST_IP));
		when(refreshTokenRepository.findByTokenHash(hashedToken)).thenReturn(Optional.of(existingToken));
		// Simulates a concurrent request already having deleted this row first.
		when(refreshTokenRepository.deleteByIdReturningCount(existingToken.id())).thenReturn(0);

		assertThrows(InvalidTokenException.class, () -> service.refresh(command));

		verify(refreshTokenRepository, never()).save(any());
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("refresh throws InvalidTokenException for a blank token before touching any repository")
	void refreshBlankTokenThrowsInvalidTokenExceptionBeforeAnyRepositoryInteraction() {
		RefreshCommand command = new RefreshCommand("   ", Optional.empty(), Optional.of(TEST_IP));

		assertThrows(InvalidTokenException.class, () -> service.refresh(command));

		verifyNoInteractions(refreshTokenRepository);
		verifyNoInteractions(jwtService);
	}

}
