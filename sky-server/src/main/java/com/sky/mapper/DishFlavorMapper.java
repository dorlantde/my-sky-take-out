package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.sky.entity.DishFlavor;
import java.util.List;

@Mapper
public interface DishFlavorMapper {
    /**
     * 批量插入口味数据
     * @param flavors
     */
    void insertBatch(List<DishFlavor> flavors);

}