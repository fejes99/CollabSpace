package com.collabspace.authworkspace.config;

import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("JWT_PRIVATE_KEY")
class SwaggerStartupListener {

	private static final Logger log = LoggerFactory.getLogger(SwaggerStartupListener.class);

	@EventListener(ApplicationReadyEvent.class)
	void onReady(ApplicationReadyEvent event) {
		Environment env = event.getApplicationContext().getEnvironment();
		String port = env.getProperty("local.server.port");
		if (port == null) {
			return;
		}
		String url = "http://localhost:" + port + "/swagger-ui.html";
		log.info("event=startup swagger_ui={}", url);
		openBrowser(url);
	}

	private static void openBrowser(String url) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("mac")) {
				new ProcessBuilder("open", url).start();
			}
			else if (os.contains("linux")) {
				new ProcessBuilder("xdg-open", url).start();
			}
			else if (os.contains("win")) {
				new ProcessBuilder("cmd", "/c", "start", url).start();
			}
		}
		catch (IOException ex) {
			log.debug("event=startup_browser_open_failed reason={}", ex.getMessage());
		}
	}

}
