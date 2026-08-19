package com.supply.demandforecast.security;

import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
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
 *
 * <p><b>A verified signature is not enough on its own.</b> The account is re-read from the
 * database on every authenticated request, and the token is honoured only if the user still
 * exists and is still enabled. That costs one indexed lookup per request, which buys the
 * thing a self-contained token cannot offer: revocation that takes effect now. Without it
 * {@code PATCH /api/users/{id}/enabled} would only stop the next login — the disabled
 * account would keep working until its token expired, up to an hour on the default expiry.
 * "Disable this account" has to mean disabled, not disabled soon.
 *
 * <p>The role is taken from that same record rather than from the {@code role} claim, since
 * the authoritative row is already in hand and ignoring half of it would be odd. A demotion
 * therefore also applies immediately, which is what closes the stale-role window {@link
 * JwtService} describes. The token's own claims are still what prove the request is genuine;
 * the database decides what that identity may currently do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

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
     * Puts the token's subject into the security context, with the role the database
     * currently holds for it.
     *
     * <p>The subject is only trustworthy because {@link JwtService#parse} has already
     * verified the signature; the authority is trustworthy because it was just read from the
     * users table rather than taken from the caller's own token.
     *
     * <p>A deleted or disabled account leaves the context empty, exactly as an expired token
     * does, so the refusal is the chain's 401 rather than a decision made here. A caller who
     * then logs in again learns why: the login endpoint answers 403 "Account disabled".
     */
    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            String username = claims.getSubject();
            if (username == null) {
                log.debug("Rejecting token with no subject");
                return;
            }

            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                log.debug("Rejecting token for '{}': no such account", username);
                return;
            }
            if (!user.isEnabled()) {
                log.debug("Rejecting token for '{}': account is disabled", username);
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    user.getUsername(),
                    null,
                    List.of(new SimpleGrantedAuthority(user.authority())));
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
