package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceException;
import com.supply.demandforecast.ratelimit.RateLimitExceededException;
import com.supply.demandforecast.service.AlertAlreadyResolvedException;
import com.supply.demandforecast.service.AlertNotFoundException;
import com.supply.demandforecast.service.CsvImportException;
import com.supply.demandforecast.service.DuplicateUsernameException;
import com.supply.demandforecast.service.InsufficientHistoryException;
import com.supply.demandforecast.service.LastAdminException;
import com.supply.demandforecast.service.PayloadTooLargeException;
import com.supply.demandforecast.service.ProductNotFoundException;
import com.supply.demandforecast.service.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Translates domain exceptions into RFC 7807 responses.
 *
 * <p>An unreachable ML service is reported as 503 rather than 500: the backend itself
 * is fine, and the caller can reasonably retry.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientHistoryException.class)
    public ProblemDetail handleInsufficientHistory(InsufficientHistoryException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Insufficient demand history");
        return problem;
    }

    /**
     * Reports an ML service failure as either 422 or 503, per {@link
     * MlServiceException#apiStatus()}.
     *
     * <p>The upstream status and body are attached as properties rather than dropped. A 422
     * from Pydantic naming the offending field is the only thing that tells the caller
     * which field to fix, and folding it into a bare "ML service unavailable" would send
     * them to retry a request that cannot succeed.
     */
    @ExceptionHandler(MlServiceException.class)
    public ProblemDetail handleMlServiceFailure(MlServiceException e) {
        HttpStatus status = e.apiStatus();
        log.error("ML service call failed, reporting {}", status, e);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.isUpstreamClientError()
                ? "ML service rejected the request"
                : "ML service unavailable");

        if (e.getUpstreamStatus() != null) {
            problem.setProperty("upstreamStatus", e.getUpstreamStatus().value());
        }
        if (e.getUpstreamBody() != null && !e.getUpstreamBody().isBlank()) {
            problem.setProperty("upstreamError", e.getUpstreamBody());
        }
        return problem;
    }

    @ExceptionHandler(CsvImportException.class)
    public ProblemDetail handleCsvImportFailure(CsvImportException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Malformed CSV upload");
        return problem;
    }

    /**
     * An upload past the multipart limit set in {@code application.yml}.
     *
     * <p>413, with a fixed detail rather than the framework's own message: that text names the byte
     * ceiling, an implementation detail the operator cannot act on beyond "send a smaller file".
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the size limit");
        problem.setTitle("Upload too large");
        return problem;
    }

    /**
     * A readable upload carrying more rows than an import will take in one call.
     *
     * <p>Also 413, not the {@link CsvImportException} 400: the file parsed fine, there was just too
     * much of it. The detail carries the row cap so the caller knows the bound it crossed.
     */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ProblemDetail handlePayloadTooLarge(PayloadTooLargeException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        problem.setTitle("Upload too large");
        return problem;
    }

    /**
     * A caller over the request budget for a rate-limited endpoint.
     *
     * <p>429 with a {@code Retry-After}, which is why this returns a {@code ResponseEntity} and not
     * a bare {@link ProblemDetail}: the header is the machine-readable half of the same answer the
     * body states in prose. The seconds are taken off the exception, already worked out by the
     * limiter, and repeated as a property so a JSON-only client need not parse the header.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Too many requests");
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * A query parameter that violates its own constraint, e.g. {@code ?page=-1}.
     *
     * <p>Needed because {@code ProductController} and {@code InventoryController} are annotated
     * {@code @Validated}, which makes Spring validate through an AOP proxy and raise this instead
     * of the {@code HandlerMethodValidationException} that MVC would answer 400 to by itself.
     * Without a handler the proxy's exception escapes as a 500 — a client sending a bad page
     * number would be told the server broke, and would get an error body the dashboard's
     * {@code ProblemDetail} parser cannot read.
     *
     * <p>The violations are joined into the detail so the response names the offending parameter
     * rather than only reporting that something was invalid.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> lastNode(violation.getPropertyPath()) + " " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, detail.isBlank() ? e.getMessage() : detail);
        problem.setTitle("Invalid request parameter");
        return problem;
    }

    /**
     * The parameter name out of a violation path like {@code list.page}.
     *
     * <p>The full path is prefixed with the controller method, which names an implementation
     * detail the caller has no way to act on.
     */
    private static String lastNode(Path propertyPath) {
        String path = propertyPath.toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    /**
     * A failed login.
     *
     * <p>Both a wrong password and an unknown username arrive here with the same message,
     * so the response cannot be used to tell which usernames exist.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Account disabled");
        return problem;
    }

    /**
     * An authenticated caller whose role does not cover the endpoint.
     *
     * <p>403, not 404: the caller is known, and the path exists. The detail names no
     * required role, because that is a hint about the shape of the system to someone who
     * has already been refused.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your role does not permit this operation");
        problem.setTitle("Forbidden");
        return problem;
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    public ProblemDetail handleDuplicateUsername(DuplicateUsernameException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Username already taken");
        return problem;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("User not found");
        return problem;
    }

    @ExceptionHandler(AlertNotFoundException.class)
    public ProblemDetail handleAlertNotFound(AlertNotFoundException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Alert not found");
        return problem;
    }

    @ExceptionHandler(AlertAlreadyResolvedException.class)
    public ProblemDetail handleAlreadyResolved(AlertAlreadyResolvedException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Alert already resolved");
        return problem;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleProductNotFound(ProductNotFoundException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Product not found");
        return problem;
    }

    @ExceptionHandler(LastAdminException.class)
    public ProblemDetail handleLastAdmin(LastAdminException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Last admin protected");
        return problem;
    }
}
