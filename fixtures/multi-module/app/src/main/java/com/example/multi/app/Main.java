package com.example.multi.app;

import com.example.multi.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LOG.info("Created {}", new OrderService().create(" order-1 ", 2));
    }
}
