package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("achievements")
public class Achievement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private String icon;
    /** COMMON / RARE / EPIC / LEGENDARY，只影响展示与排序，不影响解锁条件和奖励。 */
    private String rarity;
    private Integer rewardExp;
    private Integer rewardCoins;
}

