package com.collabspace.authworkspace.adapter.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // TODO: extract X-Correlation-ID from request header, or generate UUID v4 if absent/empty
        // TODO: truncate to 64 chars if oversized
        // TODO: store in MDC so every log line in this request carries it
        // TODO: set X-Correlation-ID on the response header
        // TODO: set Access-Control-Expose-Headers: X-Correlation-ID
        filterChain.doFilter(request, response);
        // TODO: clear MDC after the request completes
    }
}
