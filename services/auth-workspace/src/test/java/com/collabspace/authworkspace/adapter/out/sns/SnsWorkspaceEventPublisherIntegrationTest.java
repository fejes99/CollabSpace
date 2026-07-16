package com.collabspace.authworkspace.adapter.out.sns;

import com.collabspace.authworkspace.application.port.out.workspace.MemberInvitedEvent;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Real LocalStack, not a Mockito mock of SnsClient -- this is the one place proving
// SnsWorkspaceEventPublisher's actual serialization + SNS publish call works end-to-end,
// closing the gap flagged in the invite-member test coverage review: every other test
// only ever exercises this class through a mock of the WorkspaceEventPublisher port.
// See testing-strategy.md §6 (SNS/SQS: LocalStack) and §12 -- this is the trigger that
// moved LocalStack from "deferred" to in use.
@Testcontainers
@DisplayName("SnsWorkspaceEventPublisher")
class SnsWorkspaceEventPublisherIntegrationTest {

	// DEFAULT_REGION must match the Region.EU_CENTRAL_1 SnsWorkspaceEventPublisher
	// hardcodes --
	// LocalStack's SNS rejects a publish() whose topic ARN region doesn't match the
	// calling
	// client's configured region, so every client here (setup and the class under test)
	// has
	// to agree on eu-central-1, not LocalStackContainer's own default region.
	@Container
	static final LocalStackContainer localstack = new LocalStackContainer(
			DockerImageName.parse("localstack/localstack:3"))
		.withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS)
		.withEnv("DEFAULT_REGION", "eu-central-1");

	private SnsClient snsClient;

	private SqsClient sqsClient;

	private String topicArn;

	private String queueUrl;

	@BeforeEach
	void setUp() {
		// SnsWorkspaceEventPublisher resolves credentials via DefaultCredentialsProvider,
		// which checks system properties before anything else -- setting these lets the
		// class under test build its SnsClient exactly as production does (no test-only
		// constructor overload needed), while still talking only to LocalStack.
		System.setProperty("aws.accessKeyId", localstack.getAccessKey());
		System.setProperty("aws.secretAccessKey", localstack.getSecretKey());

		StaticCredentialsProvider credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));

		snsClient = SnsClient.builder()
			.endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
			.region(Region.EU_CENTRAL_1)
			.credentialsProvider(credentials)
			.build();
		sqsClient = SqsClient.builder()
			.endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
			.region(Region.EU_CENTRAL_1)
			.credentialsProvider(credentials)
			.build();

		topicArn = snsClient.createTopic(CreateTopicRequest.builder().name("workspace-events-test").build()).topicArn();
		queueUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName("workspace-events-test-queue").build())
			.queueUrl();
		String queueArn = sqsClient
			.getQueueAttributes(GetQueueAttributesRequest.builder()
				.queueUrl(queueUrl)
				.attributeNames(QueueAttributeName.QUEUE_ARN)
				.build())
			.attributes()
			.get(QueueAttributeName.QUEUE_ARN);

		// RawMessageDelivery: without it, SQS receives our JSON wrapped in SNS's own
		// notification envelope. A real consumer could go either way; raw is simpler to
		// assert against and is what this test cares about proving (the publish call
		// itself), not envelope-format compatibility.
		snsClient.subscribe(builder -> builder.topicArn(topicArn)
			.protocol("sqs")
			.endpoint(queueArn)
			.attributes(Map.of("RawMessageDelivery", "true")));
	}

	@AfterEach
	void tearDown() {
		snsClient.close();
		sqsClient.close();
		System.clearProperty("aws.accessKeyId");
		System.clearProperty("aws.secretAccessKey");
	}

	@Test
	@DisplayName("publishes a member.invited event whose body round-trips through a real SNS topic")
	void publishMemberInvitedDeliversMatchingMessageBody() {
		SnsWorkspaceEventPublisher publisher = new SnsWorkspaceEventPublisher(topicArn,
				localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString());
		UUID adminId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID invitedUserId = UUID.randomUUID();
		MemberInvitedEvent event = new MemberInvitedEvent(adminId, workspaceId, invitedUserId, "bob@example.com",
				WorkspaceRole.MEMBER, "trace-1");

		publisher.publishMemberInvited(event);

		Message message = receiveOneMessage();
		String body = message.body();
		assertThat(JsonPath.<String>read(body, "$.adminId")).isEqualTo(adminId.toString());
		assertThat(JsonPath.<String>read(body, "$.workspaceId")).isEqualTo(workspaceId.toString());
		assertThat(JsonPath.<String>read(body, "$.invitedUserId")).isEqualTo(invitedUserId.toString());
		assertThat(JsonPath.<String>read(body, "$.email")).isEqualTo("bob@example.com");
		assertThat(JsonPath.<String>read(body, "$.role")).isEqualTo("MEMBER");
		assertThat(JsonPath.<String>read(body, "$.correlationId")).isEqualTo("trace-1");
	}

	@Test
	@DisplayName("publishes with the member.invited eventType message attribute")
	void publishMemberInvitedSetsEventTypeMessageAttribute() {
		SnsWorkspaceEventPublisher publisher = new SnsWorkspaceEventPublisher(topicArn,
				localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString());
		MemberInvitedEvent event = new MemberInvitedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"bob@example.com", WorkspaceRole.MEMBER, null);

		publisher.publishMemberInvited(event);

		Message message = receiveOneMessageWithAttributes();
		assertThat(message.messageAttributes().get("eventType").stringValue()).isEqualTo("member.invited");
	}

	private Message receiveOneMessage() {
		List<Message> messages = sqsClient.receiveMessage(
				ReceiveMessageRequest.builder().queueUrl(queueUrl).waitTimeSeconds(10).maxNumberOfMessages(1).build())
			.messages();
		assertThat(messages).hasSize(1);
		return messages.get(0);
	}

	private Message receiveOneMessageWithAttributes() {
		List<Message> messages = sqsClient
			.receiveMessage(ReceiveMessageRequest.builder()
				.queueUrl(queueUrl)
				.waitTimeSeconds(10)
				.maxNumberOfMessages(1)
				.messageAttributeNames("All")
				.build())
			.messages();
		assertThat(messages).hasSize(1);
		return messages.get(0);
	}

}
