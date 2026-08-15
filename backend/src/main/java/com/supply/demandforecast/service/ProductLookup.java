package com.supply.demandforecast.service;

import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the {@code {id}} path variable that several product endpoints share.
 *
 * <p>Both forms are natural: a UI holding a product row has the id, while an import, a report or
 * an alert has the SKU. Every endpoint under {@code /api/products/{id}} accepts either, and this
 * is the one place that decides what that means — five endpoints resolving the path themselves
 * is five chances for them to disagree about it.
 */
@Component
@RequiredArgsConstructor
public class ProductLookup {

    private final ProductRepository productRepository;

    /**
     * The product for an id or a SKU.
     *
     * <p>An all-digit segment is tried as an id first and falls back to a SKU lookup, so a
     * numeric SKU still resolves. The reverse order would let a SKU shadow a real id.
     *
     * @throws ProductNotFoundException if neither interpretation matches
     */
    public Product resolve(String idOrSku) {
        if (idOrSku != null && idOrSku.chars().allMatch(Character::isDigit)) {
            try {
                Optional<Product> byId = productRepository.findById(Long.parseLong(idOrSku));
                if (byId.isPresent()) {
                    return byId.get();
                }
            } catch (NumberFormatException tooLargeForLong) {
                // Longer than a long: cannot be an id, so fall through to the SKU lookup.
            }
        }
        return productRepository.findBySku(idOrSku)
                .orElseThrow(() -> new ProductNotFoundException(idOrSku));
    }
}
