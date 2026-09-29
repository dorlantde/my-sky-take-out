package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.AddressBookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressBookServiceImpl implements AddressBookService {

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Override
    public List<AddressBook> list(AddressBook addressBook) {
        return addressBookMapper.list(addressBook);
    }

    @Override
    public void save(AddressBook addressBook) {
        addressBook.setId(null);
        addressBook.setUserId(BaseContext.getCurrentId());
        addressBook.setIsDefault(0);
        addressBookMapper.insert(addressBook);
    }

    @Override
    public AddressBook getById(Long id) {
        return getCurrentUserAddress(id);
    }

    @Override
    public void update(AddressBook addressBook) {
        getCurrentUserAddress(addressBook.getId());
        addressBook.setUserId(BaseContext.getCurrentId());
        addressBookMapper.update(addressBook);
    }

    @Override
    @Transactional
    public void setDefault(AddressBook addressBook) {
        Long userId = BaseContext.getCurrentId();
        getCurrentUserAddress(addressBook.getId());

        AddressBook resetCondition = AddressBook.builder()
                .userId(userId)
                .isDefault(0)
                .build();
        addressBookMapper.updateIsDefaultByUserId(resetCondition);

        AddressBook defaultAddress = AddressBook.builder()
                .id(addressBook.getId())
                .userId(userId)
                .isDefault(1)
                .build();
        addressBookMapper.update(defaultAddress);
    }

    @Override
    public void deleteById(Long id) {
        getCurrentUserAddress(id);
        addressBookMapper.deleteByIdAndUserId(id, BaseContext.getCurrentId());
    }

    private AddressBook getCurrentUserAddress(Long id) {
        if (id == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        AddressBook addressBook = addressBookMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        return addressBook;
    }
}
