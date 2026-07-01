package com.collabspace.authworkspace.application.service.auth;

import com.collabspace.authworkspace.application.port.in.auth.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.LoginUseCase;
import com.collabspace.authworkspace.application.port.in.auth.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.RegisterResult;
import com.collabspace.authworkspace.application.port.in.auth.RegisterUseCase;
import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.model.auth.User;
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
public class AuthApplicationService implements RegisterUseCase, LoginUseCase {

	private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

	private final UserRepository userRepository;

	private final RefreshTokenRepository refreshTokenRepository;

	private final JwtService jwtService;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	public AuthApplicationService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
			JwtService jwtService, PasswordEncoder passwordEncoder, Clock clock) {
		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
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
		String accessToken = jwtService.issueAccessToken(saved.id().toString(), List.of());
		log.info("event=user_registered userId={} emailHash={}", saved.id(), CryptoUtils.sha256Hex(normalisedEmail));
		return new RegisterResult(saved, accessToken);
	}

	@Override
	@Transactional
	public LoginResult login(LoginCommand command) {
		// Normalize email

		// Query user by email - if user don't exist throw error

		// password_hash on user is null -never attempt bcrypt on null

		// Log event=login_failed reason=not_found|bad_password|null_password_hash
		// emailHash, ip

		// Log event=user_logged_in userId, ip, userAgent

		// Comapre password hash with command password - if doesn't match, throw error

		// Issue access token

		// Issue refresh token

		// Insert refresh token in database

		throw new UnsupportedOperationException();
	}

}
