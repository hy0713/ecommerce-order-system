package com.ecommerce.controller;

import com.ecommerce.common.dto.AddressDTO;
import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址接口
 */
@Tag(name = "收货地址管理")
@RestController
@RequestMapping("/api/user/address")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @Operation(summary = "收货地址列表")
    @GetMapping("/list")
    public Result<List<UserAddress>> list() {
        return Result.success(addressService.list(UserContext.getUserId()));
    }

    @Operation(summary = "新增收货地址")
    @PostMapping
    public Result<Long> add(@Validated @RequestBody AddressDTO addressDTO) {
        return Result.success(addressService.add(UserContext.getUserId(), addressDTO));
    }

    @Operation(summary = "修改收货地址")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Validated @RequestBody AddressDTO addressDTO) {
        addressService.update(UserContext.getUserId(), id, addressDTO);
        return Result.success();
    }

    @Operation(summary = "删除收货地址")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(UserContext.getUserId(), id);
        return Result.success();
    }

    @Operation(summary = "设置默认地址")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(UserContext.getUserId(), id);
        return Result.success();
    }

    @Operation(summary = "查询地址并校验归属（Feign 调用）")
    @GetMapping("/{id}")
    public Result<UserAddress> getById(@PathVariable Long id) {
        return Result.success(addressService.getOwned(UserContext.getUserId(), id));
    }
}
