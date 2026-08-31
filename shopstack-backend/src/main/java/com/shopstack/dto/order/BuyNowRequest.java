package com.shopstack.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BuyNowRequest {
    @NotNull(message = "Product is required")
    private Long productId;

    @NotNull
    @Min(1)
    private Integer quantity = 1;

    @NotNull(message = "Shipping address is required")
    private Long shippingAddressId;

    private String couponCode;
}