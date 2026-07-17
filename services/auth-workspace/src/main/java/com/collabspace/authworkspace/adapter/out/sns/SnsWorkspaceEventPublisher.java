package com.collabspace.authworkspace.adapter.out.sns;

import com.collabspace.authworkspace.application.port.out.workspace.MemberInvitedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.net.URI;
import java.util.Map;

// Endpoint override is local-dev-only (points at LocalStack) -- when
// AWS_SNS_ENDPOINT_OVERRIDE is unset (the AWS dev environment case), SnsClient falls
// back to its normal default resolution (real AWS, credentials from the ECS task role).
@Component
public class SnsWorkspaceEventPublisher implements WorkspaceEventPublisher {

	private static final String EVENT_TYPE_MEMBER_INVITED = "member.invited";

	private final SnsClient snsClient;

	private final String topicArn;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public SnsWorkspaceEventPublisher(@Value("${SNS_WORKSPACE_EVENTS_TOPIC_ARN}") String topicArn,
			@Value("${AWS_SNS_ENDPOINT_OVERRIDE:}") String endpointOverride) {
		this.topicArn = topicArn;
		// Same region for both modes (this project only ever deploys to eu-central-1,
		// per infrastructure/environments/dev). Credentials provider is explicit but
		// still chain-resolved -- DefaultCredentialsProvider itself walks env vars,
		// profile file, container/instance credentials, same as leaving it unset would.
		var builder = SnsClient.builder()
			.region(Region.EU_CENTRAL_1)
			.credentialsProvider(DefaultCredentialsProvider.builder().build());
		if (!endpointOverride.isBlank()) {
			builder.endpointOverride(URI.create(endpointOverride));
		}
		this.snsClient = builder.build();
	}

	@Override
	public void publishMemberInvited(MemberInvitedEvent event) {
		String messageBody = writeAsJson(event);
		PublishRequest request = PublishRequest.builder()
			.topicArn(topicArn)
			.message(messageBody)
			.messageAttributes(Map.of("eventType",
					MessageAttributeValue.builder().dataType("String").stringValue(EVENT_TYPE_MEMBER_INVITED).build()))
			.build();
		snsClient.publish(request);
	}

	private String writeAsJson(MemberInvitedEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize MemberInvitedEvent", ex);
		}
	}

}
