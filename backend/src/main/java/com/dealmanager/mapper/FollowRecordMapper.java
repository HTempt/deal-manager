package com.dealmanager.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealmanager.domain.FollowRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FollowRecordMapper extends BaseMapper<FollowRecord> {
    /** Fetch one actual latest record per active customer, without loading full histories. */
    @Select("""
            SELECT r.* FROM follow_record r
            JOIN customer c ON c.id = r.customer_id AND c.user_id = r.user_id AND c.status = 'ACTIVE'
            WHERE r.user_id = #{userId} AND r.created_at <= #{now}
            AND NOT EXISTS (
                SELECT 1 FROM follow_record newer
                WHERE newer.user_id = r.user_id AND newer.customer_id = r.customer_id
                AND newer.created_at <= #{now}
                AND (newer.created_at > r.created_at OR (newer.created_at = r.created_at AND newer.id > r.id))
            )
            """)
    List<FollowRecord> selectLatestForActiveCustomers(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
