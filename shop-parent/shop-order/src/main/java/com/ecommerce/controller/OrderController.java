package com.ecommerce.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.dto.OrderCreateDTO;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.common.vo.OrderVO;
import com.ecommerce.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口
 *
 * <p>权限约定：C 端接口按归属校验（userId 从上下文取），管理端接口必须通过
 * {@link UserContext#requireAdmin()} —— 此前 ship / complete / admin-cancel / stats
 * 只要求「已登录」，任意用户可操作他人订单（越权漏洞）。
 */
@Tag(name = "订单管理")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "创建订单")
    @PostMapping
    public Result<OrderVO> create(@Validated @RequestBody OrderCreateDTO orderCreateDTO) {
        return Result.success(orderService.create(UserContext.requireUserId(), orderCreateDTO));
    }

    @Operation(summary = "模拟支付")
    @PostMapping("/{id}/pay")
    public Result<OrderVO> pay(@PathVariable Long id) {
        return Result.success(orderService.pay(UserContext.requireUserId(), id));
    }

    @Operation(summary = "取消订单")
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable Long id) {
        return Result.success(orderService.cancel(UserContext.requireUserId(), id));
    }

    @Operation(summary = "发货（管理员）")
    @PostMapping("/{id}/ship")
    public Result<OrderVO> ship(@PathVariable Long id) {
        UserContext.requireAdmin();
        return Result.success(orderService.ship(id));
    }

    @Operation(summary = "完成订单（管理员）")
    @PostMapping("/{id}/complete")
    public Result<OrderVO> complete(@PathVariable Long id) {
        UserContext.requireAdmin();
        return Result.success(orderService.complete(id));
    }

    @Operation(summary = "订单分页列表（管理员为全站，普通用户仅本人）")
    @GetMapping("/page")
    public Result<Page<OrderVO>> page(@RequestParam(required = false) Integer orderStatus,
                                      @RequestParam(defaultValue = "1") int pageNum,
                                      @RequestParam(defaultValue = "10") int pageSize) {
        // 此前恒传 requireUserId()，导致管理后台只能看到管理员自己下的单、看不到客户订单，
        // 而同页的 /stats 是全局聚合，同一页面口径自相矛盾。
        // 这里管理员传 null 表示不加 user_id 过滤，普通用户仍严格限定本人。
        Long scopeUserId = UserContext.isAdmin() ? null : UserContext.requireUserId();
        return Result.success(orderService.page(scopeUserId, orderStatus, pageNum, pageSize));
    }

    @Operation(summary = "订单详情（管理员可查任意订单）")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        // 与 page 保持同一口径：管理员可查看全站任意订单详情，普通用户仅本人
        Long scopeUserId = UserContext.isAdmin() ? null : UserContext.requireUserId();
        return Result.success(orderService.detail(scopeUserId, id));
    }

    @Operation(summary = "管理端取消订单（管理员）")
    @PostMapping("/{id}/admin-cancel")
    public Result<OrderVO> adminCancel(@PathVariable Long id) {
        UserContext.requireAdmin();
        return Result.success(orderService.adminCancel(id));
    }

    @Operation(summary = "订单统计（管理员）")
    @GetMapping("/stats")
    public Result<java.util.Map<String, Object>> stats() {
        UserContext.requireAdmin();
        return Result.success(orderService.stats());
    }
}
