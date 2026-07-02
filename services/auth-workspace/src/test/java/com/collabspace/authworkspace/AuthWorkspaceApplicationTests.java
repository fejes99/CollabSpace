package com.collabspace.authworkspace;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestContainersConfiguration.class)
@DisplayName("Application context")
class AuthWorkspaceApplicationTests {

	@Test
	@DisplayName("context loads without errors")
	void contextLoads() {
		// Passes if the Spring context starts without errors
	}

}
