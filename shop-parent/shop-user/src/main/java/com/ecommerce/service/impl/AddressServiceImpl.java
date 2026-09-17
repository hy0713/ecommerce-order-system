package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.dto.AddressDTO;
import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.mapper.UserAddressMapper;
import com.ecommerce.service.AddressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 收货地址服务实现
 */
@Service
public class AddressServiceImpl implements AddressService {

    private final UserAddressMapper userAddressMapper;

    public AddressServiceImpl(UserAddressMapper userAddressMapper) {
        this.userAddressMapper = userAddressMapper;
    }

    @Override
    public List<UserAddress> list(Long userId) {
        return userAddressMapper.selectList(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .orderByDesc(UserAddress::getIsDefault)
                .orderByDesc(UserAddress::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(Long userId, AddressDTO addressDTO) {
        boolean isFirst = userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)) == 0;
        boolean asDefault = isFirst || Integer.valueOf(1).equals(addressDTO.getIsDefault());

        if (asDefault) {
            cancelAllDefault(userId);
        }
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setReceiverName(addressDTO.getReceiverName());
        address.setReceiverPhone(addressDTO.getReceiverPhone());
        address.setAddress(addressDTO.getAddress());
        address.setIsDefault(asDefault ? 1 : 0);
        userAddressMapper.insert(address);
        return address.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long id, AddressDTO addressDTO) {
        UserAddress address = getOwned(userId, id);
        if (Integer.valueOf(1).equals(addressDTO.getIsDefault()) && address.getIsDefault() != 1) {
            cancelAllDefault(userId);
        }
        address.setReceiverName(addressDTO.getReceiverName());
        address.setReceiverPhone(addressDTO.getReceiverPhone());
        address.setAddress(addressDTO.getAddress());
        address.setIsDefault(Integer.valueOf(1).equals(addressDTO.getIsDefault()) ? 1 : address.getIsDefault());
        userAddressMapper.updateById(address);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        UserAddress address = getOwned(userId, id);
        userAddressMapper.deleteById(id);
        // 若删除的是默认地址，将剩余第一条设为默认
        if (address.getIsDefault() == 1) {
            UserAddress first = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                    .eq(UserAddress::getUserId, userId)
                    .orderByDesc(UserAddress::getCreateTime)
                    .last("limit 1"));
            if (first != null) {
                first.setIsDefault(1);
                userAddressMapper.updateById(first);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        getOwned(userId, id);
        // 先取消该用户原有默认地址，再设置新默认，保证唯一默认
        cancelAllDefault(userId);
        userAddressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getUserId, userId)
                .set(UserAddress::getIsDefault, 1));
    }

    @Override
    public UserAddress getOwned(Long userId, Long id) {
        UserAddress address = userAddressMapper.selectById(id);
        if (address == null || !Objects.equals(address.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_FOUND);
        }
        return address;
    }

    private void cancelAllDefault(Long userId) {
        userAddressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1)
                .set(UserAddress::getIsDefault, 0));
    }
}
