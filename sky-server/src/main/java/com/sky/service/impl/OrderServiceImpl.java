package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private WebSocketServer webSocketServer;

    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        Long userId = BaseContext.getCurrentId();

        AddressBook addressBook = addressBookMapper.getByIdAndUserId(
                ordersSubmitDTO.getAddressBookId(), userId);
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        ShoppingCart condition = ShoppingCart.builder().userId(userId).build();
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(condition);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        int packAmount = ordersSubmitDTO.getPackAmount() == null
                ? 0 : Math.max(ordersSubmitDTO.getPackAmount(), 0);
        BigDecimal goodsAmount = shoppingCartList.stream()
                .map(cart -> cart.getAmount().multiply(BigDecimal.valueOf(cart.getNumber())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderAmount = goodsAmount.add(BigDecimal.valueOf(packAmount));

        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, order);
        order.setNumber(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setPhone(addressBook.getPhone());
        order.setAddress(buildFullAddress(addressBook));
        order.setConsignee(addressBook.getConsignee());
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());
        order.setAmount(orderAmount);
        order.setPackAmount(packAmount);
        if (order.getPayMethod() <= 0) {
            order.setPayMethod(1);
        }
        if (order.getDeliveryStatus() == null) {
            order.setDeliveryStatus(1);
        }
        if (order.getTablewareStatus() == null) {
            order.setTablewareStatus(1);
        }

        orderMapper.insert(order);

        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setId(null);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        shoppingCartMapper.deleteByUserId(userId);

        return OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();
    }

    /**
     * 本地模拟支付。只允许当前用户支付自己的待付款订单。
     */
    @Override
    @Transactional
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) {
        if (ordersPaymentDTO == null || ordersPaymentDTO.getOrderNumber() == null
                || ordersPaymentDTO.getOrderNumber().trim().isEmpty()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Orders order = orderMapper.getByNumberAndUserId(
                ordersPaymentDTO.getOrderNumber(), BaseContext.getCurrentId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (Orders.PAID.equals(order.getPayStatus())) {
            throw new OrderBusinessException("该订单已支付");
        }
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Integer payMethod = ordersPaymentDTO.getPayMethod() == null
                ? order.getPayMethod() : ordersPaymentDTO.getPayMethod();
        Orders paidOrder = Orders.builder()
                .id(order.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .payMethod(payMethod)
                .checkoutTime(LocalDateTime.now())
                .build();
        orderMapper.update(paidOrder);

        // 本地模拟支付成功后，向所有已连接的管理端页面推送来单提醒。
        sendOrderMessage(1, order);

        return OrderPaymentVO.builder()
                .nonceStr("local-test")
                .paySign("LOCAL_PAYMENT_SUCCESS")
                .timeStamp(String.valueOf(Instant.now().getEpochSecond()))
                .signType("LOCAL")
                .packageStr("local_order=" + order.getNumber())
                .build();
    }

    @Override
    public PageResult pageQueryForUser(int page, int pageSize, Integer status) {
        PageHelper.startPage(normalizePage(page), normalizePageSize(pageSize));

        OrdersPageQueryDTO query = new OrdersPageQueryDTO();
        query.setUserId(BaseContext.getCurrentId());
        query.setStatus(status);
        Page<Orders> ordersPage = orderMapper.pageQuery(query);

        List<OrderVO> records = ordersPage.getResult().stream()
                .map(this::buildOrderVO)
                .collect(Collectors.toList());
        return new PageResult(ordersPage.getTotal(), records);
    }

    @Override
    public OrderVO userDetails(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        return buildExistingOrderVO(order);
    }

    @Override
    @Transactional
    public void userCancelById(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus())
                && !Orders.TO_BE_CONFIRMED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders cancelledOrder = Orders.builder()
                .id(order.getId())
                .status(Orders.CANCELLED)
                .cancelReason("用户取消")
                .cancelTime(LocalDateTime.now())
                .build();
        if (Orders.PAID.equals(order.getPayStatus())) {
            // 本地测试模式：用退款状态代替真实微信退款。
            cancelledOrder.setPayStatus(Orders.REFUND);
        }
        orderMapper.update(cancelledOrder);
    }

    @Override
    @Transactional
    public void repetition(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(order.getId());
        if (orderDetails == null || orderDetails.isEmpty()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Long userId = BaseContext.getCurrentId();
        for (OrderDetail detail : orderDetails) {
            ShoppingCart condition = ShoppingCart.builder()
                    .userId(userId)
                    .dishId(detail.getDishId())
                    .setmealId(detail.getSetmealId())
                    .dishFlavor(detail.getDishFlavor())
                    .build();
            List<ShoppingCart> existingList = shoppingCartMapper.list(condition);
            if (existingList != null && !existingList.isEmpty()) {
                ShoppingCart existing = existingList.get(0);
                existing.setNumber(existing.getNumber() + detail.getNumber());
                shoppingCartMapper.updateNumberById(existing);
            } else {
                ShoppingCart cart = new ShoppingCart();
                BeanUtils.copyProperties(detail, cart);
                cart.setId(null);
                cart.setUserId(userId);
                cart.setCreateTime(LocalDateTime.now());
                shoppingCartMapper.insert(cart);
            }
        }
    }

    @Override
    public void reminder(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Integer status = order.getStatus();
        if (!Orders.TO_BE_CONFIRMED.equals(status)
                && !Orders.CONFIRMED.equals(status)
                && !Orders.DELIVERY_IN_PROGRESS.equals(status)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        sendOrderMessage(2, order);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO query) {
        PageHelper.startPage(normalizePage(query.getPage()), normalizePageSize(query.getPageSize()));
        Page<Orders> ordersPage = orderMapper.pageQuery(query);
        List<OrderVO> records = ordersPage.getResult().stream()
                .map(this::buildOrderVO)
                .collect(Collectors.toList());
        return new PageResult(ordersPage.getTotal(), records);
    }

    @Override
    public OrderStatisticsVO statistics() {
        OrderStatisticsVO result = new OrderStatisticsVO();
        result.setToBeConfirmed(orderMapper.countByStatus(Orders.TO_BE_CONFIRMED));
        result.setConfirmed(orderMapper.countByStatus(Orders.CONFIRMED));
        result.setDeliveryInProgress(orderMapper.countByStatus(Orders.DELIVERY_IN_PROGRESS));
        return result;
    }

    @Override
    public OrderVO details(Long id) {
        return buildExistingOrderVO(orderMapper.getById(id));
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders order = getExistingOrder(ordersConfirmDTO == null ? null : ordersConfirmDTO.getId());
        if (!Orders.TO_BE_CONFIRMED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orderMapper.update(Orders.builder()
                .id(order.getId())
                .status(Orders.CONFIRMED)
                .build());
    }

    @Override
    @Transactional
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        Orders order = getExistingOrder(ordersRejectionDTO == null ? null : ordersRejectionDTO.getId());
        if (!Orders.TO_BE_CONFIRMED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders rejectedOrder = Orders.builder()
                .id(order.getId())
                .status(Orders.CANCELLED)
                .rejectionReason(defaultReason(
                        ordersRejectionDTO.getRejectionReason(), "商家拒单"))
                .cancelTime(LocalDateTime.now())
                .build();
        if (Orders.PAID.equals(order.getPayStatus())) {
            rejectedOrder.setPayStatus(Orders.REFUND);
        }
        orderMapper.update(rejectedOrder);
    }

    @Override
    @Transactional
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        Orders order = getExistingOrder(ordersCancelDTO == null ? null : ordersCancelDTO.getId());
        if (Orders.COMPLETED.equals(order.getStatus()) || Orders.CANCELLED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders cancelledOrder = Orders.builder()
                .id(order.getId())
                .status(Orders.CANCELLED)
                .cancelReason(defaultReason(ordersCancelDTO.getCancelReason(), "商家取消"))
                .cancelTime(LocalDateTime.now())
                .build();
        if (Orders.PAID.equals(order.getPayStatus())) {
            cancelledOrder.setPayStatus(Orders.REFUND);
        }
        orderMapper.update(cancelledOrder);
    }

    @Override
    public void delivery(Long id) {
        Orders order = getExistingOrder(id);
        if (!Orders.CONFIRMED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orderMapper.update(Orders.builder()
                .id(order.getId())
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build());
    }

    @Override
    public void complete(Long id) {
        Orders order = getExistingOrder(id);
        if (!Orders.DELIVERY_IN_PROGRESS.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orderMapper.update(Orders.builder()
                .id(order.getId())
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build());
    }

    private OrderVO buildExistingOrderVO(Orders order) {
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return buildOrderVO(order);
    }

    private OrderVO buildOrderVO(Orders order) {
        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(order.getId());
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setOrderDetailList(orderDetails);
        orderVO.setOrderDishes(orderDetails.stream()
                .map(detail -> detail.getName() + "*" + detail.getNumber())
                .collect(Collectors.joining("；")));
        return orderVO;
    }

    private Orders getExistingOrder(Long id) {
        if (id == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Orders order = orderMapper.getById(id);
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return order;
    }

    private int normalizePage(int page) {
        return page > 0 ? page : 1;
    }

    private int normalizePageSize(int pageSize) {
        return pageSize > 0 ? pageSize : 10;
    }

    private String defaultReason(String reason, String defaultValue) {
        return reason == null || reason.trim().isEmpty() ? defaultValue : reason.trim();
    }

    private void sendOrderMessage(int type, Orders order) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("orderId", order.getId());
        message.put("content", "订单号：" + order.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(message));
    }

    private String buildFullAddress(AddressBook addressBook) {
        StringBuilder address = new StringBuilder();
        appendAddressPart(address, addressBook.getProvinceName());
        appendAddressPart(address, addressBook.getCityName());
        appendAddressPart(address, addressBook.getDistrictName());
        appendAddressPart(address, addressBook.getDetail());
        return address.toString();
    }

    private void appendAddressPart(StringBuilder address, String part) {
        if (part != null && !part.trim().isEmpty()) {
            address.append(part.trim());
        }
    }
}
