package com.supply.demandforecast.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Charges the {@link TokenBucketRateLimiter} once per request to a rate-limited endpoint, and
 * turns a denial into a {@link RateLimitExceededException} → 429.
 *
 * <p>A {@code HandlerInterceptor} rather than a servlet filter on purpose: it runs inside the
 * {@code DispatcherServlet}, so the exception it throws is caught by {@code GlobalExceptionHandler}
 * and rendered as the same RFC-7807 body every other error uses — a filter that threw would land
 * on the container's error page instead. Running after the security chain is a feature, not a
 * limitation: the caller is already authenticated, so keying by principal is reliable, and the
 * cheap requests this protects against never reach the expensive work behind them.
 *
 * <p>Scoped to its target paths in {@code WebConfig}, so it never fires on an ordinary read. The
 * key is {@code route-group | caller}: uploads and the scan get independent budgets, and the
 * caller is the authenticated principal, falling back to the client IP for anything anonymous
 * (which the security rules make unreachable for these endpoints anyway).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties properties;
    private final TokenBucketRateLimiter limiter;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }

        String key = routeGroup(request) + '|' + caller(request);
        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(key);
        if (decision.allowed()) {
            return true;
        }

        log.warn("Rate limit hit for [{}] on {} {}",
                key, request.getMethod(), request.getRequestURI());
        throw new RateLimitExceededException(decision.retryAfterSeconds());
    }

    /** The scan and the uploads are limited separately; a scan burst must not starve an upload. */
    private static String routeGroup(HttpServletRequest request) {
        return request.getRequestURI().endsWith("/scan") ? "scan" : "upload";
    }

    private static String caller(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
