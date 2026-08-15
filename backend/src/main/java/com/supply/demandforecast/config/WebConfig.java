package com.supply.demandforecast.config;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer type conversion.
 *
 * <p>Query parameters are bound by Spring's conversion service, not by Jackson, so the
 * {@code @JsonValue} on {@link Severity} that emits {@code "high"} in a response body has no
 * effect on {@code ?severity=high} coming in — the default enum converter would call {@code
 * valueOf("high")} and fail. An API that prints a value it will not accept back is a trap, so
 * both enums get a case-insensitive converter here.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Registers both converters with their types stated explicitly.
     *
     * <p>The three-argument overload is required, not stylistic: a lambda returned from a
     * generic method carries no reifiable type arguments, so the single-argument form cannot
     * work out {@code <S>} and {@code <T>} and refuses the registration at startup.
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Severity.class, lenient(Severity.class));
        registry.addConverter(String.class, AlertType.class, lenient(AlertType.class));
    }

    /**
     * A converter accepting any casing, and treating blank as absent.
     *
     * <p>Blank matters because {@code ?severity=} is what an unset form field or a naive URL
     * builder sends. Read as "no severity filter" rather than rejected as an invalid one.
     */
    private static <E extends Enum<E>> Converter<String, E> lenient(Class<E> type) {
        return source -> source == null || source.isBlank()
                ? null
                : Enum.valueOf(type, source.trim().toUpperCase());
    }
}
