package com.supply.demandforecast.config;

import com.supply.demandforecast.client.MlServiceProperties;
import com.supply.demandforecast.service.InventoryScanProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Shared application beans. The {@link RestClient} defined here is the transport used by
 * {@code client/} classes to reach the ML service over the internal network.
 */
@Configuration
@EnableConfigurationProperties({MlServiceProperties.class, InventoryScanProperties.class})
public class AppConfig {

    /**
     * The ML service transport.
     *
     * <p>{@code defaultStatusHandler} is turned off for error statuses so the client can
     * decide what is retryable. Left on, Spring would raise the exception before
     * {@code MlServiceClient} ever saw the status code, and a 5xx worth retrying would be
     * indistinguishable from a 422 that never will be.
     */
    @Bean
    public RestClient mlServiceRestClient(RestClient.Builder builder,
                                          MlServiceProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeout()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeout()));

        return builder
                .baseUrl(properties.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    // Deliberately inert: MlServiceClient inspects the status itself.
                })
                .build();
    }

    /**
     * Applies the same timeouts to any other {@link RestClient} built in this application,
     * so a future client cannot accidentally inherit an unbounded default.
     */
    @Bean
    public RestClientCustomizer restClientTimeoutCustomizer(MlServiceProperties properties) {
        return builder -> builder.requestFactory(
                ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofMillis(properties.connectTimeout()))
                                .withReadTimeout(Duration.ofMillis(properties.readTimeout()))));
    }
}
