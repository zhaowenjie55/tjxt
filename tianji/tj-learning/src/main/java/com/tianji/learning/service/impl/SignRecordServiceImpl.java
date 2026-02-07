package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BooleanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;

    private final RabbitMqHelper mqHelper;


    /**
     * 用 Redis bitmap 记录当天签到，防止重复签到；
     * 然后统计连续签到天数，计算奖励积分，发送 MQ 消息完成积分记录。
     * @return
     */
    @Override
    public SignResultVO addSignRecords() {
        // 1.签到
// 1.1.获取登录用户
        Long userId = UserContext.getUser();
// 1.2.获取日期
        LocalDate now = LocalDate.now();
// 1.3.拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
// 1.4.计算offset
        int offset = now.getDayOfMonth() - 1; //bitMap 从位置 0 开始。
// 1.5.保存签到信息
        Boolean exists = redisTemplate.opsForValue().setBit(key, offset, true); //返回旧值（true 表示以前已经签到过）
        if (BooleanUtils.isTrue(exists)) {
            // oldValue=true 说明今天已签到，禁止重复
            throw new BizIllegalException("不允许重复签到！");
        }
// 2.计算连续签到天数
        int signDays = countSignDays(key, now.getDayOfMonth());
// 3.计算签到得分
        int rewardPoints = 0;
        switch (signDays) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }
// 4.保存积分明细记录
/*
 •给用户记录 rewardPoints + 1 分
    •为什么 +1？
     →签到本身就给 1 积分，连续签到额外奖励是 rewardPoints。
*/
        mqHelper.send(
                MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));
// 5.封装返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(signDays);
        vo.setRewardPoints(rewardPoints);
        return vo;


    }


    @Override
    public Byte[] querySignRecords() {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.获取日期
        LocalDate now = LocalDate.now();
        int dayOfMonth = now.getDayOfMonth();
        // 3.拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        // 4.读取
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        // 	BitFieldType.unsigned(dayOfMonth)：定义一个 无符号整数类型，位宽 = dayOfMonth
                        // 	比如今天 18 号 → 取一个 18 位的无符号数
                        BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        // 	.valueAt(0)：
        //从第 0 位开始算起（也就是从 1 号那一天的 bit 开始）
        if (CollUtils.isEmpty(result)) {
            return new Byte[0];
        }
        int num = result.get(0).intValue();

        Byte[] arr = new Byte[dayOfMonth];
        int pos = dayOfMonth - 1;
        while (pos >= 0){
            arr[pos--] = (byte)(num & 1);
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return arr;


    }

    private int countSignDays(String key, int len) {
        // 1.获取本月从第一天开始，到今天为止的所有签到记录
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(len)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            return 0;
        }
        int num = result.get(0).intValue();
        // 2.定义一个计数器
        int count = 0;
        // 3.循环，与1做与运算，得到最后一个bit，判断是否为0，为0则终止，为1则继续
        while ((num & 1) == 1) {
            // 4.计数器+1
            count++;
            // 5.把数字右移一位，最后一位被舍弃，倒数第二位成了最后一位
            num >>>= 1;
        }
        return count;
    }
}
