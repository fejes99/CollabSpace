package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.adapter.in.rest.security.filter.HeaderAuthenticationFilter;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.InternalTokenFilter;
import com.collabspace.authworkspace.adapter.in.rest.security.filter.JwtBlocklistFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final InternalTokenFilter internalTokenFilter;

	private final HeaderAuthenticationFilter headerAuthenticationFilter;

	private final JwtBlocklistFilter jwtBlocklistFilter;

	private final ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	public SecurityConfig(InternalTokenFilter internalTokenFilter,
			HeaderAuthenticationFilter headerAuthenticationFilter, JwtBlocklistFilter jwtBlocklistFilter,
			ProblemDetailsSecurityHandler problemDetailsSecurityHandler) {
		this.internalTokenFilter = internalTokenFilter;
		this.headerAuthenticationFilter = headerAuthenticationFilter;
		this.jwtBlocklistFilter = jwtBlocklistFilter;
		this.problemDetailsSecurityHandler = problemDetailsSecurityHandler;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// Order per plan §4: InternalTokenFilter -> HeaderAuthenticationFilter
			// -> JwtBlocklistFilter.
			.addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(headerAuthenticationFilter, InternalTokenFilter.class)
			.addFilterAfter(jwtBlocklistFilter, HeaderAuthenticationFilter.class)
			.exceptionHandling(handling -> handling.authenticationEntryPoint(problemDetailsSecurityHandler)
				.accessDeniedHandler(problemDetailsSecurityHandler))
			// Mirrors SecurityExemptPaths so this authorization layer can't drift
			// from the filters' own path exemptions once anyRequest() is tightened
			// for @PreAuthorize (PR 8) -- until then, anyRequest().permitAll()
			// covers these anyway, so this line only guards against future drift,
			// not current behavior.
			.authorizeHttpRequests(auth -> auth.requestMatchers("/.well-known/**", "/swagger-ui/**", "/v3/api-docs/**")
				.permitAll()
				.anyRequest()
				.permitAll())
			.build();
	}

}
