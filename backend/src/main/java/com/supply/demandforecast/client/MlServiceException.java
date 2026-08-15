package com.supply.demandforecast.client;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Raised when a call to the ML service fails.
 *
 * <p>The point of this type is to <em>keep</em> what the ML service said rather than
 * flattening every failure into "something went wrong". A 422 from Pydantic naming the
 * offending field is genuinely useful to the caller, and {@code GlobalExceptionHandler}
 * turns it into a matching API error instead of a blanket 503.
 *
 * @see #getUpstreamStatus()
 * @see #getUpstreamBody()
 */
public class MlServiceException extends RuntimeException {

    /** Null when the call never produced an HTTP response, e.g. a connect timeout. */
    private final HttpStatusCode upstreamStatus;

    /** The raw error body from the ML service, when it sent one. */
    private final String upstreamBody;

    public MlServiceException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public MlServiceException(String message) {
        this(message, null, null, null);
    }

    public MlServiceException(String message,
                              Throwable cause,
                              HttpStatusCode upstreamStatus,
                              String upstreamBody) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public HttpStatusCode getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamBody() {
        return upstreamBody;
    }

    /**
     * Whether the ML service rejected the request itself, rather than being unavailable.
     *
     * <p>Drives the API status code: a 4xx upstream means this backend sent something
     * the ML service would reject again, so replaying it is pointless and reporting 503
     * ("try later") would be a lie.
     */
    public boolean isUpstreamClientError() {
        return upstreamStatus != null && upstreamStatus.is4xxClientError();
    }

    /** The status this failure should surface as on the backend's own API. */
    public HttpStatus apiStatus() {
        return isUpstreamClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.SERVICE_UNAVAILABLE;
    }
}
