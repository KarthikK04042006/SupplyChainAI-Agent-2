package com.supply.demandforecast.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates a request from its {@code Authorization: Bearer} header.
 *
 * <p>A missing or unparseable token is not an error here — the filter simply leaves the
 * security context empty and lets the chain decide. That keeps the "who are you" question
 * separate from "are you allowed", so a public endpoint stays reachable with a stale token
 * in the header and a protected one still returns 401 through the normal entry point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }
        chain.doFilter(request, response);
    }

    /**
     * Puts the token's subject and role into the security context.
     *
     * <p>The authority comes from the {@code role} claim, which is only trustworthy
     * because {@link JwtService#parse} has already verified the signature.
     */
    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            String role = claims.get(JwtService.ROLE_CLAIM, String.class);
            if (claims.getSubject() == null || role == null) {
                log.debug("Rejecting token with no subject or role claim");
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException e) {
            // Expired, forged, or malformed. Left unauthenticated; the message is logged
            // but never echoed to the caller, and the token itself is never logged.
            log.debug("Rejecting bearer token: {}", e.getMessage());
        }
    }

    /** The raw token, or null when the header is absent or not a bearer credential. */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
