package com.collabspace.authworkspace.adapter.out.ssm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty("JWT_PRIVATE_KEY_SSM_PATH")
public class SsmConfigLoader {

	private final SsmClient ssmClient;

	public SsmConfigLoader() {
		ssmClient = SsmClient.create();
	}

	// One round trip for all requested paths instead of one per path -- see plan
	// batch-startup-ssm-fetch.md. GetParameters reports unresolved names in
	// invalidParameters() rather than throwing, so that list must be checked
	// explicitly or a missing parameter silently becomes a null downstream.
	public Map<String, String> getParameters(List<String> paths) {
		GetParametersRequest request = GetParametersRequest.builder().names(paths).withDecryption(true).build();
		GetParametersResponse response = ssmClient.getParameters(request);
		if (!response.invalidParameters().isEmpty()) {
			throw new IllegalStateException("Missing SSM parameters: " + response.invalidParameters());
		}
		return response.parameters().stream().collect(Collectors.toMap(Parameter::name, Parameter::value));
	}

}
