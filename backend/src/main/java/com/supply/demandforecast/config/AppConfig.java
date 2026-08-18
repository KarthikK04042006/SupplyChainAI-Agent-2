package com.supply.demandforecast.config;

import com.supply.demandforecast.client.MlServiceProperties;
import com.supply.demandforecast.logging.RequestIdClientInterceptor;
import com.supply.demandforecast.ratelimit.RateLimitProperties;
import com.supply.demandforecast.service.InventoryScanProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared application beans. The {@link RestClient} defined here is the transport used by
 * {@code client/} classes to reach the ML service over the internal network.
 */
@Configuration
@EnableConfigurationProperties({MlServiceProperties.class, InventoryScanProperties.class,
        RateLimitProperties.class})
public class AppConfig {

    /**
     * The ML service transport.
     *
     * <p>Error statuses are left to the default handler, which raises for 4xx and 5xx.
     * {@code MlServiceClient} does not depend on that: it POSTs through {@code exchange},
     * which bypasses status handlers, and classifies the status itself. The default is kept
     * for the one call that does not — the {@code /health} probe — where raising is the
     * right outcome anyway.
     *
     * <p><b>Pinned to HTTP/1.1.</b> The JDK's {@code HttpClient} defaults to HTTP/2, which
     * over cleartext means it opens with an {@code h2c} upgrade
     * ({@code Upgrade: h2c} plus {@code Connection: Upgrade, HTTP2-Settings}). Uvicorn
     * speaks HTTP/1.1 only, and in declining that upgrade it discards the request body:
     * FastAPI then rejects the call with {@code 422 "body: Field required"} for a request
     * whose JSON was perfectly well formed. Negotiating a protocol the server does not
     * support gains nothing here — this is one internal hop to a Python service — so the
     * upgrade is simply not offered.
     */
    @Bean
    public RestClient mlServiceRestClient(RestClient.Builder builder,
                                          MlServiceProperties properties) {
        return builder
                .baseUrl(properties.url())
                .requestFactory(http11RequestFactory(properties))
                .requestInterceptor(new RequestIdClientInterceptor())
                .build();
    }

    /**
     * A JDK-backed request factory carrying the configured timeouts and speaking HTTP/1.1.
     *
     * <p>{@code jdk()} rather than {@code detect()}: the choice is no longer incidental
     * once the protocol version matters, and naming it means adding an HTTP client to the
     * classpath cannot quietly switch the transport back to one that offers {@code h2c}.
     */
    private ClientHttpRequestFactory http11RequestFactory(MlServiceProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeout()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeout()));

        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                .build(settings);
    }

    /**
     * Applies the same timeouts to any other {@link RestClient} built in this application,
     * so a future client cannot accidentally inherit an unbounded default.
     *
     * <p>Shares the HTTP/1.1 pinning for the same reason: a client added later is more
     * likely than not to be talking to the same Python service, and a transport that
     * offers {@code h2c} fails there in a way that looks like a payload bug.
     */
    @Bean
    public RestClientCustomizer restClientTimeoutCustomizer(MlServiceProperties properties) {
        return builder -> builder.requestFactory(http11RequestFactory(properties));
    }
}
