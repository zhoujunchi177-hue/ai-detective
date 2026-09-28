package com.mindtrace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindtrace.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 取该用户行上的排他锁，用来把「同一个玩家的并发写」串行化。
     * <p>
     * 需要它的场景：线索解锁是「读—判断—写」三步，两个并发请求会双双判定
     * 「尚未发现」然后都去插入，后提交的那个撞唯一键失败；改用
     * {@code INSERT IGNORE} 也不行 —— 它会在唯一键上取共享锁，两个事务互等
     * 直接变成死锁（实测报 DeadlockLoserDataAccessException）。
     * <p>
     * 先按固定顺序锁住用户行，再动该用户的名下数据，就把冲突面收敛成
     * 「同一用户串行、不同用户互不影响」。必须在事务内调用。
     *
     * @return 用户 id；用户不存在时返回 null
     */
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") Long userId);
}
