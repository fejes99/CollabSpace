package com.collabspace.authworkspace.application.port.out.auth;

import com.collabspace.authworkspace.domain.model.auth.User;

import java.util.Optional;

public interface UserRepository {

	Optional<User> findByEmail(String email);

	User save(User user);

}
