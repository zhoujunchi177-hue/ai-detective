package com.mindtrace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindtrace.entity.UserClue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface UserClueMapper extends BaseMapper<UserClue> {

    /**
     * 幂等写入：该线索若已发现则静默跳过。
     * <p>
     * 刻意不用「先 selectCount 再 insert」的写法。那是典型的 check-then-act 竞态：
     * 玩家连续调查两个地点时，两个事务会同时读到「尚未发现」，然后都去插入，
     * 后提交的那个撞上 {@code uk_user_clue} 唯一键，**整次调查失败**。
     * 现在把去重交给唯一键本身（{@code INSERT IGNORE}），重复的那次只返回 0 行，
     * 调查照常成功。
     *
     * @return 真正插入的行数；0 表示这条线索之前就已经发现过
     */
    @Insert("INSERT IGNORE INTO user_clues (user_id, case_id, clue_id, discovered_at, source) "
            + "VALUES (#{userId}, #{caseId}, #{clueId}, #{discoveredAt}, #{source})")
    int insertIgnore(@Param("userId") Long userId,
                     @Param("caseId") Long caseId,
                     @Param("clueId") Long clueId,
                     @Param("discoveredAt") LocalDateTime discoveredAt,
                     @Param("source") String source);
}
