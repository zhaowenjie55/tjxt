package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType type) {
        LocalDateTime now = LocalDateTime.now();
        int maxPoints = type.getMaxPoints();
        int realPoints = points;
        if (maxPoints > 0) {
            LocalDateTime begin = DateUtils.getDayStartTime(now);
            LocalDateTime end = DateUtils.getDayEndTime(now);
            int currentPoints = queryUserPointsByTypeAndDate(userId, type, begin, end);
            if (currentPoints >= maxPoints) {
                return;
            }
            if (currentPoints + points > maxPoints) {
                realPoints = maxPoints - currentPoints;
            }
        }
        PointsRecord p = new PointsRecord();
        p.setPoints(realPoints);
        p.setUserId(userId);
        p.setType(type);
        save(p);

        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);

    }

    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        // 1. 获取当前登录用户（ThreadLocal 中保存）
        Long user = UserContext.getUser();

        // 2. 计算“今天”的时间范围
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now); // 今天 00:00:00
        LocalDateTime end = DateUtils.getDayEndTime(now);     // 今天 23:59:59

        // 3. 构建查询条件：当前用户 + 今天时间范围
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, user)
                .between(PointsRecord::getCreateTime, begin, end);

        // 4. 调用自定义 mapper 方法
        //    注意：这里通常是按积分类型做了聚合（SUM + GROUP BY）
        List<PointsRecord> list = getBaseMapper().queryUserPointsByDate(wrapper);

        // 5. 如果今天没有任何积分记录，直接返回空列表
        if (CollUtils.isEmpty(list)) {
            return new ArrayList<>();
        }

        // 6. PO → VO 转换，封装前端需要的数据结构
        List<PointsStatisticsVO> vos = new ArrayList<>(list.size());
        for (PointsRecord p : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();

            // 积分类型描述（来自枚举）
            vo.setType(p.getType().getDesc());

            // 该积分类型的每日上限（来自枚举规则）
            vo.setMaxPoints(p.getType().getMaxPoints());

            // 今天该类型实际获得的积分
            vo.setPoints(p.getPoints());

            vos.add(vo);
        }

        // 7. 返回今日积分统计结果
        return vos;
    }

    private int queryUserPointsByTypeAndDate(
            Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        // 1.查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(type != null, PointsRecord::getType, type)
                .between(begin != null && end != null, PointsRecord::getCreateTime, begin, end);
        // 2.调用mapper，查询结果
        Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);
        // 3.判断并返回
        return points == null ? 0 : points;
    }
}
