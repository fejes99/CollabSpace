package com.collabspace.authworkspace.application.port.out.auth;

import com.collabspace.authworkspace.domain.model.auth.User;

public interface UserRepository {

	User save(User user);

}
