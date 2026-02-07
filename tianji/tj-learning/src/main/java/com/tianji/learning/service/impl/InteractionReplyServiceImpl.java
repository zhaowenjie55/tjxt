package com.tianji.learning.service.impl;

import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.remark.RemarkClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_CREATE_TIME;
import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_LIKED_TIME;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {

    private final IInteractionQuestionService questionService;
    private final UserClient userClient;
    private final RemarkClient remarkClient;
    private final RabbitMqHelper mqHelper;

    @Override
    @Transactional
    public void saveReply(ReplyDTO replyDTO) {

        // 1) 获取当前登录用户ID
        Long userId = UserContext.getUser();

        // 2) 新增回复（可能是“回答”也可能是“评论”）
        InteractionReply reply = BeanUtils.toBean(replyDTO, InteractionReply.class);
        reply.setUserId(userId); // 防止前端伪造userId
        save(reply);             // 插入后 reply.getId() 才是本次新回复的主键

        // 3) 判断这次回复类型：answerId == null -> 回答；否则 -> 评论
        boolean isAnswer = replyDTO.getAnswerId() == null;

        // 3.1) 如果是评论：给被评论的“上级回答”的评论数 reply_times + 1
        if (!isAnswer) {
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")
                    .eq(InteractionReply::getId, replyDTO.getAnswerId())
                    .update();
        }

        // 3.2) 更新问题表（interaction_question）
        // - 如果是回答：更新 latest_answer_id = 本次新插入回复的 id
        // - 如果是回答：answer_times + 1
        // - 如果是学生：将状态改为待审核（如果你们业务只允许“学生回答触发审核”，可加 isAnswer && isStudent）
        boolean isStudent = Boolean.TRUE.equals(replyDTO.getIsStudent());

        questionService.lambdaUpdate()
                // ✅ 关键修正：最新回答ID 应该是 reply.getId()
                .set(isAnswer, InteractionQuestion::getLatestAnswerId, reply.getId())
                .setSql(isAnswer, "answer_times = answer_times + 1")
                // 如需更严格：.set(isAnswer && isStudent, InteractionQuestion::getStatus, UN_CHECK)
                .set(isStudent, InteractionQuestion::getStatus, QuestionStatus.UN_CHECK.getValue())
                .eq(InteractionQuestion::getId, replyDTO.getQuestionId())
                .update();

        // 4) 学生回复才加积分：发MQ给积分系统（异步）
        if (isStudent) {
            mqHelper.send(
                    MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.WRITE_REPLY,
                    5
            );
        }
    }

    /**
     * queryReplyPage() 用于查询某问题下的回答、或某回答下的评论。
     * 支持匿名、管理员视角、目标回复用户昵称、点赞状态的查询，并且通过大量的“批量查询 + Map 缓存”来提升性能。
     * @param query
     * @param forAdmin
     * @return
     */
    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, boolean forAdmin) {

        // 1) 参数校验：问题id / 回答id 至少提供一个
        Long questionId = query.getQuestionId();
        Long answerId = query.getAnswerId();
        if (questionId == null && answerId == null) {
            throw new BadRequestException("问题或回答id不能都为空");
        }

        // 2) 判断查询类型：questionId 有值 -> 查“问题下的回答”；否则 -> 查“回答下的评论”
        boolean isQueryAnswer = questionId != null;

        // 3) 分页查询回复（interaction_reply）
        Page<InteractionReply> page = lambdaQuery()
                // 3.1 查回答：where question_id = ?（查评论时不需要这个条件）
                .eq(isQueryAnswer, InteractionReply::getQuestionId, questionId)
                // 3.2 查回答：answer_id = 0；查评论：answer_id = answerId
                .eq(InteractionReply::getAnswerId, isQueryAnswer ? 0L : answerId)
                // 3.3 非管理员需要过滤隐藏回复：hidden=false；管理员不过滤
                .eq(!forAdmin, InteractionReply::getHidden, false)
                // 3.4 排序：先按点赞字段，再按创建时间（同点赞情况下更早/更晚看配置）
                .page(query.toMpPage(
                        new OrderItem(DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(DATA_FIELD_NAME_CREATE_TIME, true)
                ));

        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 4) 批量准备数据：用户信息、目标回复信息、点赞状态（避免N+1）
        Set<Long> userIds = new HashSet<>();
        Set<Long> replyIds = new HashSet<>();        // 用于查询点赞状态（bizLiked）
        Set<Long> targetReplyIds = new HashSet<>();  // 用于查询目标回复（拿目标用户信息）

        // 4.1 收集：回复人id、目标回复id、当前回复id
        for (InteractionReply r : records) {
            // 匿名：普通用户不展示用户信息；管理员可以看
            if (!Boolean.TRUE.equals(r.getAnonymity()) || forAdmin) {
                userIds.add(r.getUserId());
            }
            targetReplyIds.add(r.getTargetReplyId());
            replyIds.add(r.getId());
        }

        // 4.2 查询目标回复：如果目标回复非匿名，则把目标回复的 userId 也加入 userIds
        targetReplyIds.remove(null);
        targetReplyIds.remove(0L);
        if (!targetReplyIds.isEmpty()) {
            List<InteractionReply> targetReplies = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream()
                    .filter(tr -> !Boolean.TRUE.equals(tr.getAnonymity()) || forAdmin)
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            userIds.addAll(targetUserIds);
        }

        // 4.3 批量查询用户信息：userId -> UserDTO
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (!userIds.isEmpty()) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            if (CollUtils.isNotEmpty(users)) {
                userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
            }
        }

        // 4.4 查询当前用户点赞状态：返回我点过赞的 replyId 集合
        Set<Long> bizLiked = remarkClient.isBizLiked(replyIds);

        // 5) 组装 VO
        List<ReplyVO> list = new ArrayList<>(records.size());
        for (InteractionReply r : records) {

            // 5.1 拷贝基础字段（PO -> VO）
            ReplyVO v = BeanUtils.toBean(r, ReplyVO.class);

            // 5.2 回复人信息（匿名控制）
            if (!Boolean.TRUE.equals(r.getAnonymity()) || forAdmin) {
                UserDTO userDTO = userMap.get(r.getUserId());
                if (userDTO != null) {
                    v.setUserIcon(userDTO.getIcon());
                    v.setUserName(userDTO.getName());
                    v.setUserType(userDTO.getType());
                }
            }

            // 5.3 目标用户信息（回复了某人时展示）
            if (r.getTargetReplyId() != null && r.getTargetReplyId() != 0L) {
                UserDTO targetUser = userMap.get(r.getTargetUserId());
                if (targetUser != null) {
                    v.setTargetUserName(targetUser.getName());
                }
            }

            // 5.4 点赞状态
            v.setLiked(bizLiked.contains(r.getId()));

            list.add(v);
        }

        // 6) 返回分页结果
        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }

    @Override
    public void hiddenReply(Long id, Boolean hidden) {

    }

    @Override
    public ReplyVO queryReplyById(Long id) {
        // 1.根据id查询
        InteractionReply r = getById(id);
        // 2.数据处理，需要查询用户信息、评论目标信息、当前用户是否点赞
        Set<Long> userIds = new HashSet<>();
        // 2.1.获取用户 id
        userIds.add(r.getUserId());
        // 2.2.查询评论目标，如果评论目标不是匿名，则需要查询出目标回复的用户id
        if(r.getTargetReplyId() != null && r.getTargetReplyId() != 0) {
            InteractionReply target = getById(r.getTargetReplyId());
            if(!target.getAnonymity()) {
                userIds.add(target.getUserId());
            }
        }
        // 2.3.查询用户详细
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if(userIds.size() > 0) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }
        // 2.4.查询用户点赞状态
        Set<Long> bizLiked = remarkClient.isBizLiked(CollUtils.singletonList(id));
        // 4.处理VO
        // 4.1.拷贝基础属性
        ReplyVO v = BeanUtils.toBean(r, ReplyVO.class);
        // 4.2.回复人信息
        UserDTO userDTO = userMap.get(r.getUserId());
        if (userDTO != null) {
            v.setUserIcon(userDTO.getIcon());
            v.setUserName(userDTO.getName());
            v.setUserType(userDTO.getType());
        }

        // 4.3.目标用户
        UserDTO targetUser = userMap.get(r.getTargetUserId());
        if (targetUser != null) {
            v.setTargetUserName(targetUser.getName());
        }
        // 4.4.点赞状态
        v.setLiked(bizLiked.contains(id));
        return v;
    }
}
