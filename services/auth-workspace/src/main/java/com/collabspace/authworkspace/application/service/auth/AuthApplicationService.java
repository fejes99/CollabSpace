package com.collabspace.authworkspace.application.service.auth;

import com.collabspace.authworkspace.application.port.in.auth.command.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RefreshCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.result.RefreshResult;
import com.collabspace.authworkspace.application.port.in.auth.result.RegisterResult;
import com.collabspace.authworkspace.application.port.in.auth.usecase.LoginUseCase;
import com.collabspace.authworkspace.application.port.in.auth.usecase.RefreshUseCase;
import com.collabspace.authworkspace.application.port.in.auth.usecase.RegisterUseCase;
import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.service.RefreshTokenPair;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.application.util.RefreshTokenValidator;
import com.collabspace.authworkspace.domain.exception.auth.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.exception.auth.ExpiredRefreshTokenException;
import com.collabspace.authworkspace.domain.exception.auth.InvalidCredentialsException;
import com.collabspace.authworkspace.domain.exception.auth.InvalidTokenException;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
import com.collabspace.authworkspace.domain.model.auth.User;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthApplicationService implements RegisterUseCase, LoginUseCase, RefreshUseCase {

	private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

	private final UserRepository userRepository;

	private final RefreshTokenRepository refreshTokenRepository;

	private final WorkspaceMembershipRepository workspaceMembershipRepository;

	private final JwtService jwtService;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	public AuthApplicationService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
			WorkspaceMembershipRepository workspaceMembershipRepository, JwtService jwtService,
			PasswordEncoder passwordEncoder, Clock clock) {
		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.workspaceMembershipRepository = workspaceMembershipRepository;
		this.jwtService = jwtService;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Override
	@Transactional
	public RegisterResult register(RegisterCommand command) {
		String normalisedEmail = command.email().toLowerCase();
		Instant now = clock.instant();
		User user = new User(UUID.randomUUID(), command.name(), normalisedEmail,
				Optional.of(passwordEncoder.encode(command.password())), now, now);
		User saved;
		try {
			saved = userRepository.save(user);
		}
		catch (EmailAlreadyTakenException ex) {
			log.warn("event=registration_rejected reason=duplicate_email emailHash={}",
					CryptoUtils.sha256Hex(normalisedEmail));
			throw ex;
		}
		AccessToken accessToken = jwtService.issueAccessToken(saved.id(), List.of());
		log.info("event=user_registered userId={} emailHash={} ip={} jti={}", saved.id(),
				CryptoUtils.sha256Hex(normalisedEmail), command.ipAddress().orElse(null), accessToken.jti());
		return new RegisterResult(saved, accessToken.token());
	}

	@Override
	@Transactional
	public LoginResult login(LoginCommand command) {
		String normalisedEmail = command.email().toLowerCase();
		String emailHash = CryptoUtils.sha256Hex(normalisedEmail);

		User user = userRepository.findByEmail(normalisedEmail)
			.orElseThrow(() -> loginFailed("not_found", emailHash, command.ipAddress()));

		String storedHash = user.passwordHash()
			.orElseThrow(() -> loginFailed("null_password_hash", emailHash, command.ipAddress()));

		if (!passwordEncoder.matches(command.password(), storedHash)) {
			throw loginFailed("bad_password", emailHash, command.ipAddress());
		}

		AccessToken accessToken = jwtService.issueAccessToken(user.id(), List.of());
		RefreshTokenPair tokenPair = jwtService.issueRefreshToken();

		Instant now = clock.instant();
		refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), user.id(), tokenPair.hash(), now,
				now.plusSeconds(JwtService.REFRESH_TOKEN_TTL_SECONDS), command.userAgent(), command.ipAddress()));

		log.info("event=user_logged_in userId={} ip={} userAgent={} jti={}", user.id(),
				command.ipAddress().orElse(null), command.userAgent().orElse(null), accessToken.jti());

		return new LoginResult(user, accessToken.token(), tokenPair.plaintext());
	}

	// noRollbackFor is deliberate: the expired-token branch below deletes the row
	// before throwing, and that delete must survive -- plain @Transactional rolls back
	// on any unchecked exception by default, which would silently undo it (see
	// RefreshExpiredTokenPersistenceIntegrationTest).
	@Override
	@Transactional(noRollbackFor = ExpiredRefreshTokenException.class)
	public RefreshResult refresh(RefreshCommand command) {
		RefreshTokenValidator.validate(command.token());

		Instant now = clock.instant();
		String hashedToken = CryptoUtils.sha256Hex(command.token());

		RefreshToken existingToken = refreshTokenRepository.findByTokenHash(hashedToken)
			.orElseThrow(InvalidTokenException::new);

		if (existingToken.expiresAt().isBefore(now)) {
			refreshTokenRepository.deleteByIdReturningCount(existingToken.id());
			throw new ExpiredRefreshTokenException();
		}

		int deletedCount = refreshTokenRepository.deleteByIdReturningCount(existingToken.id());
		if (deletedCount == 0) {
			throw new InvalidTokenException();
		}

		RefreshTokenPair tokenPair = jwtService.issueRefreshToken();
		refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), existingToken.userId(), tokenPair.hash(), now,
				now.plusSeconds(JwtService.REFRESH_TOKEN_TTL_SECONDS), command.userAgent(), command.ipAddress()));

		List<WorkspaceMembership> userMemberships = workspaceMembershipRepository.findByUserId(existingToken.userId());
		AccessToken accessToken = jwtService.issueAccessToken(existingToken.userId(), userMemberships);

		log.info("event=token_refreshed userId={} ip={} jti={}", existingToken.userId(),
				command.ipAddress().orElse(null), accessToken.jti());

		return new RefreshResult(accessToken.token(), tokenPair.plaintext());
	}

	private InvalidCredentialsException loginFailed(String reason, String emailHash, Optional<String> ip) {
		log.warn("event=login_failed reason={} emailHash={} ip={}", reason, emailHash, ip.orElse(null));
		return new InvalidCredentialsException();
	}

}
