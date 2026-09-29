package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时处理长时间未发生状态变化的订单。
 */
@Component
@Slf4j
public class OrderTask {

    private static final long PAYMENT_TIMEOUT_MINUTES = 15L;
    private static final long DELIVERY_TIMEOUT_MINUTES = 60L;

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 每分钟检查一次，自动取消超过15分钟仍未支付的订单。
     */
    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    @Transactional
    public void processTimeoutOrder() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusMinutes(PAYMENT_TIMEOUT_MINUTES);
        List<Orders> orders = orderMapper.getByStatusAndOrderTimeBefore(
                Orders.PENDING_PAYMENT, deadline);

        for (Orders order : orders) {
            Orders cancelledOrder = Orders.builder()
                    .id(order.getId())
                    .status(Orders.CANCELLED)
                    .cancelReason("支付超时，自动取消")
                    .cancelTime(now)
                    .build();
            orderMapper.update(cancelledOrder);
        }

        if (!orders.isEmpty()) {
            log.info("已自动取消支付超时订单，数量：{}", orders.size());
        }
    }

    /**
     * 每天凌晨1点检查，自动完成下单超过1小时且仍处于派送中的订单。
     */
    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Shanghai")
    @Transactional
    public void processDeliveryOrder() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusMinutes(DELIVERY_TIMEOUT_MINUTES);
        List<Orders> orders = orderMapper.getByStatusAndOrderTimeBefore(
                Orders.DELIVERY_IN_PROGRESS, deadline);

        for (Orders order : orders) {
            Orders completedOrder = Orders.builder()
                    .id(order.getId())
                    .status(Orders.COMPLETED)
                    .deliveryTime(now)
                    .build();
            orderMapper.update(completedOrder);
        }

        if (!orders.isEmpty()) {
            log.info("已自动完成长时间派送中的订单，数量：{}", orders.size());
        }
    }
}
