package com.micro_service.order.service.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryClientFallbackTest {

    private final InventoryClient client = new InventoryClient() {
        @Override
        public com.micro_service.order.service.api.InventoryResponse checkStock(String sku, Integer quantity) {
            throw new UnsupportedOperationException("Stub: invoke fallback directly");
        }
    };

    @Test
    void unavailableInventoryIsNotMisreportedAsOutOfStock() {
        var cause = new IllegalStateException("Connection refused");

        assertThatThrownBy(() -> client.fallbackMethod("keyboard-001", 1, cause))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCause()).isSameAs(cause);
                });
    }
}
