package com.shopstack.service;

import com.shopstack.dto.coupon.ApplyCouponResult;
import com.shopstack.dto.order.*;
import com.shopstack.entity.*;
import com.shopstack.enums.OrderStatus;
import com.shopstack.enums.PaymentStatus;
import com.shopstack.exception.BadRequestException;
import com.shopstack.exception.ResourceNotFoundException;
import com.shopstack.repository.AddressRepository;
import com.shopstack.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final CartService cartService;
    private final InventoryService inventoryService;
    private final ProductService productService;
    private final PaymentService paymentService;
    private final UserService userService;
    private final MailService mailService;
    private final CouponService couponService;
    private final WarehouseTaskService warehouseTaskService;

    /** A single line to purchase: a product plus how many units. Used by both cart checkout and Buy Now. */
    private record OrderLine(Product product, int quantity) {}

    /**
     * Creates the order in PENDING state from the customer's current cart,
     * reserves stock for every line item, and opens a Razorpay order for
     * checkout. Nothing is deducted from stock permanently until payment is verified.
     */
    @Transactional
    public OrderResponse checkout(Long userId, CreateOrderRequest request) {
        Cart cart = cartService.getOrCreateCart(userId);

        if (cart.getItems().isEmpty()) {
            throw new BadRequestException("Your cart is empty");
        }

        List<OrderLine> lines = cart.getItems().stream()
                .map(item -> new OrderLine(item.getProduct(), item.getQuantity()))
                .toList();

        OrderResponse response = buildAndCreateOrder(userId, request.getShippingAddressId(), lines, request.getCouponCode());

        cartService.clearCart(userId);

        return response;
    }

    /**
     * Buys a single product directly, skipping the cart entirely — the
     * customer's existing cart contents (if any) are left untouched.
     */
    @Transactional
    public OrderResponse buyNow(Long userId, BuyNowRequest request) {
        Product product = productService.getById(request.getProductId());
        List<OrderLine> lines = List.of(new OrderLine(product, request.getQuantity()));

        return buildAndCreateOrder(userId, request.getShippingAddressId(), lines, request.getCouponCode());
    }

    private OrderResponse buildAndCreateOrder(Long userId, Long shippingAddressId, List<OrderLine> lines, String couponCode) {
        User user = userService.getById(userId);

        Address address = addressRepository.findById(shippingAddressId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipping address not found"));
        if (!address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Shipping address not found");
        }

        // Reserve stock for every item first — fail fast before creating anything if any item is short.
        for (OrderLine line : lines) {
            inventoryService.reserveStock(line.product().getId(), line.quantity());
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .user(user)
                .shippingAddress(address)
                .status(OrderStatus.PENDING)
                .build();

        for (OrderLine line : lines) {
            Product product = line.product();
            BigDecimal unitPrice = product.getDiscountPrice() != null ? product.getDiscountPrice() : product.getPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.add(lineTotal);

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .vendor(product.getVendor())
                    .productNameSnapshot(product.getName())
                    .quantity(line.quantity())
                    .priceAtPurchase(unitPrice)
                    .build();
            orderItems.add(orderItem);
        }

        order.setItems(orderItems);
        BigDecimal discountAmount = BigDecimal.ZERO;
        String appliedCouponCode = null;
        if (couponCode != null && !couponCode.isBlank()) {
            ApplyCouponResult result = couponService.validateAndCompute(couponCode, subtotal);
            discountAmount = result.getDiscountAmount();
            appliedCouponCode = result.getCode();
        }

        order.setSubtotal(subtotal);
        order.setShippingFee(BigDecimal.ZERO); // flat/free shipping for now
        order.setDiscountAmount(discountAmount);
        order.setCouponCode(appliedCouponCode);
        order.setTotalAmount(subtotal.subtract(discountAmount));

        order = orderRepository.save(order);

        Payment payment = paymentService.createRazorpayOrder(order);

        return toResponse(order, payment);
    }

    /**
     * Called after the client completes checkout with Razorpay. Verifies the
     * signature server-side (never trust the client's "success" claim alone),
     * then permanently deducts stock and confirms the order.
     */
    @Transactional
    public OrderResponse verifyAndConfirmPayment(Long userId, VerifyPaymentRequest request) {
        boolean valid = paymentService.verifySignature(
                request.getRazorpayOrderId(), request.getRazorpayPaymentId(), request.getRazorpaySignature());

        if (!valid) {
            paymentService.markFailed(request.getRazorpayOrderId(), "Signature verification failed");
            throw new BadRequestException("Payment verification failed. If money was deducted, it will be refunded automatically by Razorpay.");
        }

        Payment payment = paymentService.markSuccess(
                request.getRazorpayOrderId(), request.getRazorpayPaymentId(), request.getRazorpaySignature());

        Order order = payment.getOrder();
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        order.setStatus(OrderStatus.CONFIRMED);
        for (OrderItem item : order.getItems()) {
            inventoryService.confirmStockDeduction(item.getProduct().getId(), item.getQuantity());
        }
        order = orderRepository.save(order);

        if (order.getCouponCode() != null) {
            couponService.markUsed(order.getCouponCode());
        }
        warehouseTaskService.createForOrder(order);

        mailService.sendOrderConfirmationEmail(order.getUser().getEmail(), order.getOrderNumber());

        return toResponse(order, payment);
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);

        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
            throw new BadRequestException("Shipped or delivered orders cannot be cancelled directly — please raise a return request instead");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("This order is already cancelled");
        }

        for (OrderItem item : order.getItems()) {
            if (order.getStatus() == OrderStatus.PENDING) {
                inventoryService.releaseReservedStock(item.getProduct().getId(), item.getQuantity());
            } else {
                inventoryService.restockAfterCancellation(item.getProduct().getId(), item.getQuantity());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        Payment payment = null;
        try {
            payment = paymentService.getByOrderId(order.getId());
        } catch (ResourceNotFoundException ignored) {
            // no payment record yet — nothing further to do
        }

        return toResponse(order, payment);
    }

    public Page<OrderResponse> getMyOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(order -> toResponse(order, safePayment(order)));
    }

    public Page<OrderResponse> getVendorOrders(Long vendorId, Pageable pageable) {
        return orderRepository.findByVendorId(vendorId, pageable)
                .map(order -> toResponse(order, safePayment(order)));
    }

    public OrderResponse getOrderDetail(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);
        return toResponse(order, safePayment(order));
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        order.setStatus(newStatus);
        order = orderRepository.save(order);
        return toResponse(order, safePayment(order));
    }

    private Order getOwnedOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return order;
    }

    private Payment safePayment(Order order) {
        try {
            return paymentService.getByOrderId(order.getId());
        } catch (ResourceNotFoundException ex) {
            return null;
        }
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int randomPart = ThreadLocalRandom.current().nextInt(100000, 999999);
        return "SS-" + datePart + "-" + randomPart;
    }

    private OrderResponse toResponse(Order order, Payment payment) {
        List<OrderItemResponse> itemResponses = order.getItems().stream().map(item ->
                OrderItemResponse.builder()
                        .productId(item.getProduct().getId())
                        .productName(item.getProductNameSnapshot())
                        .quantity(item.getQuantity())
                        .priceAtPurchase(item.getPriceAtPurchase())
                        .lineTotal(item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                        .build()
        ).toList();

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .status(order.getStatus().name())
                .subtotal(order.getSubtotal())
                .discountAmount(order.getDiscountAmount())
                .shippingFee(order.getShippingFee())
                .totalAmount(order.getTotalAmount())
                .couponCode(order.getCouponCode())
                .items(itemResponses)
                .razorpayOrderId(payment != null ? payment.getRazorpayOrderId() : null)
                .paymentStatus(payment != null ? payment.getStatus().name() : PaymentStatus.PENDING.name())
                .createdAt(order.getCreatedAt())
                .build();
    }
}