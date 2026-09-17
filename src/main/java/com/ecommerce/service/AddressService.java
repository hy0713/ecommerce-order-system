package com.ecommerce.service;

import com.ecommerce.dto.AddressDTO;
import com.ecommerce.entity.UserAddress;

import java.util.List;

/**
 * 收货地址服务
 */
public interface AddressService {

    /**
     * 地址列表
     */
    List<UserAddress> list(Long userId);

    /**
     * 新增地址，返回地址ID
     */
    Long add(Long userId, AddressDTO addressDTO);

    /**
     * 修改地址
     */
    void update(Long userId, Long id, AddressDTO addressDTO);

    /**
     * 删除地址
     */
    void delete(Long userId, Long id);

    /**
     * 设置默认地址（先取消原默认，保证唯一默认）
     */
    void setDefault(Long userId, Long id);
}
