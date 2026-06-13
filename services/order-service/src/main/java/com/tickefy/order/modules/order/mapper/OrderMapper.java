package com.tickefy.order.modules.order.mapper;

import com.tickefy.order.modules.order.dto.OrderItemResponse;
import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.OrderItemEntity;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public OrderResponse toResponse(OrderEntity order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentUrl(),
                order.getExpiresAt(),
                items);
    }

    public OrderItemResponse toItemResponse(OrderItemEntity item) {
        return new OrderItemResponse(
                item.getTicketTypeId(),
                item.getQuantity(),
                item.getUnitPrice());
    }
}
