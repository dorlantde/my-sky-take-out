package com.sky.controller.user;

import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("userOrderController")
@RequestMapping("/user/order")
@Slf4j
@Api(tags = "C端-订单接口")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/submit")
    @ApiOperation("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) {
        log.info("用户下单：{}", ordersSubmitDTO);
        return Result.success(orderService.submitOrder(ordersSubmitDTO));
    }

    /**
     * 本地开发模拟支付：不调用微信支付，直接完成支付状态流转。
     */
    @PutMapping("/payment")
    @ApiOperation("订单支付（本地模拟）")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) {
        log.info("本地模拟订单支付：{}", ordersPaymentDTO);
        return Result.success(orderService.payment(ordersPaymentDTO));
    }

    @GetMapping("/historyOrders")
    @ApiOperation("查询历史订单")
    public Result<PageResult> historyOrders(int page, int pageSize, Integer status) {
        return Result.success(orderService.pageQueryForUser(page, pageSize, status));
    }

    @GetMapping("/orderDetail/{id}")
    @ApiOperation("查询订单详情")
    public Result<OrderVO> details(@PathVariable Long id) {
        return Result.success(orderService.userDetails(id));
    }

    @PutMapping("/cancel/{id}")
    @ApiOperation("取消订单")
    public Result<String> cancel(@PathVariable Long id) {
        orderService.userCancelById(id);
        return Result.success();
    }

    @PostMapping("/repetition/{id}")
    @ApiOperation("再来一单")
    public Result<String> repetition(@PathVariable Long id) {
        orderService.repetition(id);
        return Result.success();
    }
}
