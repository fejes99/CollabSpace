package com.collabspace.authworkspace;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class AuthWorkspaceApplicationTests {

	@Test
	void contextLoads() {
		// Passes if the Spring context starts without errors
	}

}
