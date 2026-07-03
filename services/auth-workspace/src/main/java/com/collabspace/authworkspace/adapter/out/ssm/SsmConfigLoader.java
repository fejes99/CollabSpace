package com.collabspace.authworkspace.adapter.out.ssm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Component
@ConditionalOnProperty("JWT_PRIVATE_KEY_SSM_PATH")
public class SsmConfigLoader {

	private final SsmClient ssmClient;

	public SsmConfigLoader() {
		ssmClient = SsmClient.create();
	}

	public String getParameter(String path) {
		GetParameterRequest parameterRequest = GetParameterRequest.builder().name(path).withDecryption(true).build();
		return ssmClient.getParameter(parameterRequest).parameter().value();
	}

}
