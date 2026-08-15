package com.supply.demandforecast.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes 401 and 403 responses for requests the filter chain refuses.
 *
 * <p>Both roles are filled by one class because the two responses must stay consistent:
 * they are the only errors produced above the {@code DispatcherServlet}, so
 * {@code GlobalExceptionHandler} never sees them and cannot shape them.
 *
 * <p><b>The status is written directly rather than through {@code sendError}.</b> Spring
 * Security's default {@link org.springframework.security.web.access.AccessDeniedHandlerImpl}
 * calls {@code sendError}, which asks the container to render an error page, which means a
 * second pass through the filter chain as an {@code ERROR} dispatch to {@code /error}. That
 * second pass is the problem: this application is stateless, so no security context survives
 * into it, and an authenticated caller who was refused for lacking a role arrives at
 * {@code /error} anonymous. The chain then challenges them as unauthenticated and the 403
 * that was already decided is overwritten with a 401 — "log in", to someone who is logged
 * in, about a role no login will grant. Committing the response here ends the request, so
 * there is no second pass to lose the answer in.
 *
 * <p>A {@link ProblemDetail} body is written for the same reason every other error carries
 * one: the dashboard reads {@code detail} to tell the user what happened, and an empty body
 * leaves it printing the bare status.
 */
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler, AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * An authenticated caller whose role does not cover the endpoint.
     *
     * <p>403, not 404: the caller is known and the path exists. The detail names no required
     * role — that describes the shape of the system to someone already refused.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException denied) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not permit this operation");
    }

    /**
     * A caller with no usable credentials.
     *
     * <p>No {@code WWW-Authenticate} header: a browser credential popup is useless for a
     * token API and only confuses a {@code fetch()} caller.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failed) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Authentication is required for this endpoint");
    }

    private void write(HttpServletResponse response, HttpStatus status,
                       String title, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
