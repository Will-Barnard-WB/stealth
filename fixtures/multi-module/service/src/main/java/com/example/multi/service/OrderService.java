package com.example.multi.service;

import com.example.multi.api.Order;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;

public class OrderService {

    public Order create(String id, int quantity) {
        Preconditions.checkArgument(quantity > 0, "quantity must be positive");
        return new Order(StringUtils.trim(id), quantity);
    }
}
