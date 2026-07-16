package com.collabspace.authworkspace.application.port.out.workspace;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MembershipStalenessRepository {

	void markMembershipChanged(UUID userId, Instant changedAt);

	Optional<Instant> findMembershipChangedAt(UUID userId);

}
