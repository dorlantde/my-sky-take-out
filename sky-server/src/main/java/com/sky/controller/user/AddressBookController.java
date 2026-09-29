package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.result.Result;
import com.sky.service.AddressBookService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/addressBook")
@Api(tags = "C端-地址簿接口")
public class AddressBookController {

    @Autowired
    private AddressBookService addressBookService;

    @GetMapping("/list")
    @ApiOperation("查询当前用户的全部地址")
    public Result<List<AddressBook>> list() {
        AddressBook condition = AddressBook.builder()
                .userId(BaseContext.getCurrentId())
                .build();
        return Result.success(addressBookService.list(condition));
    }

    @PostMapping
    @ApiOperation("新增地址")
    public Result<String> save(@RequestBody AddressBook addressBook) {
        addressBookService.save(addressBook);
        return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("根据ID查询地址")
    public Result<AddressBook> getById(@PathVariable Long id) {
        return Result.success(addressBookService.getById(id));
    }

    @PutMapping
    @ApiOperation("修改地址")
    public Result<String> update(@RequestBody AddressBook addressBook) {
        addressBookService.update(addressBook);
        return Result.success();
    }

    @PutMapping("/default")
    @ApiOperation("设置默认地址")
    public Result<String> setDefault(@RequestBody AddressBook addressBook) {
        addressBookService.setDefault(addressBook);
        return Result.success();
    }

    @DeleteMapping
    @ApiOperation("删除地址")
    public Result<String> deleteById(@RequestParam Long id) {
        addressBookService.deleteById(id);
        return Result.success();
    }

    @GetMapping("/default")
    @ApiOperation("查询默认地址")
    public Result<AddressBook> getDefault() {
        AddressBook condition = AddressBook.builder()
                .userId(BaseContext.getCurrentId())
                .isDefault(1)
                .build();
        List<AddressBook> list = addressBookService.list(condition);
        if (list != null && !list.isEmpty()) {
            return Result.success(list.get(0));
        }
        return Result.error("没有查询到默认地址");
    }
}
