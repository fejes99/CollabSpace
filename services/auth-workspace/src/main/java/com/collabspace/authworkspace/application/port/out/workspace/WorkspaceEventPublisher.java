package com.collabspace.authworkspace.application.port.out.workspace;

public interface WorkspaceEventPublisher {

	void publishMemberInvited(MemberInvitedEvent event);

	void publishRoleChanged(MemberRoleChangedEvent event);

	void publishMemberRemoved(MemberRemovedEvent event);

}
