package com.tianji.learning.service.impl;

import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final InteractionReplyMapper replyMapper;
    private final UserClient userClient;
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;
    private final IInteractionReplyService iInteractionReplyService;

    @Override
    public void saveQuestion(QuestionFormDTO questionDTO) {
        // 1.获取当前登录的用户id
        Long userId = UserContext.getUser();
        // 2.数据封装
        InteractionQuestion question = BeanUtils.copyBean(questionDTO, InteractionQuestion.class);
        question.setUserId(userId);
        // 3.写入数据库
        save(question);
    }


    /**
     * queryQuestionPage 的核心逻辑是：
     * 先根据课程/小节分页查询问题列表，然后收集所有相关的用户 ID 和回答 ID，
     * 统一批量查询用户信息和回答信息，最后按顺序封装到 QuestionVO 中并返回分页结果。
     * 通过批量查询避免了典型的 N+1 性能问题，同时处理了匿名逻辑与最新回答逻辑。
     * @param query
     * @return
     */
    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {

        // 1) 参数校验：课程id、小节id 必须有
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if (courseId == null || sectionId == null) {
            throw new BadRequestException("课程ID和小节ID不能为空");
        }

        // 2) 分页查询问题列表（interaction_question）
        Page<InteractionQuestion> page = lambdaQuery()
                // 2.1 不查询 description（一般是大字段，列表页不需要，减小开销）
                .select(InteractionQuestion.class, info -> !info.getProperty().equals("description"))
                // 2.2 onlyMine=true 时才加 “只看我的” 条件
                .eq(Boolean.TRUE.equals(query.getOnlyMine()), InteractionQuestion::getUserId, UserContext.getUser())
                // 2.3 固定条件：课程 + 小节
                .eq(InteractionQuestion::getCourseId, courseId)
                .eq(InteractionQuestion::getSectionId, sectionId)
                // 2.4 只查未隐藏的问题
                .eq(InteractionQuestion::getHidden, false)
                // 2.5 分页 + 默认按创建时间倒序
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 3) 收集要批量查询的数据：用户id、最新回答id（避免N+1）
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();

        for (InteractionQuestion q : records) {
            // 3.1 匿名提问者不查用户信息
            if (!Boolean.TRUE.equals(q.getAnonymity())) {
                userIds.add(q.getUserId());
            }
            // 3.2 收集最新回答id（可能为null）
            answerIds.add(q.getLatestAnswerId());
        }

        // 去掉 null，避免 selectBatchIds 里出现 null
        answerIds.remove(null);

        // 4) 批量查询最新回答，并转成 map：answerId -> reply
        Map<Long, InteractionReply> replyMap = new HashMap<>(answerIds.size());
        if (CollUtils.isNotEmpty(answerIds)) {
            List<InteractionReply> replies = replyMapper.selectBatchIds(answerIds);
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(), reply);

                // 4.1 匿名回答者不查用户信息
                if (!Boolean.TRUE.equals(reply.getAnonymity())) {
                    userIds.add(reply.getUserId());
                }
            }
        }

        // 5) 批量查询用户信息，并转 map：userId -> UserDTO
        userIds.remove(null);
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            if (CollUtils.isNotEmpty(users)) {
                userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
            }
        }

        // 6) PO -> VO 并补齐展示字段（用户名/头像/最新回复内容/最新回复人）
        List<QuestionVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion r : records) {

            // 6.1 基础字段拷贝（PO -> VO）
            QuestionVO vo = BeanUtils.copyBean(r, QuestionVO.class);

            // 6.2 提问者信息（非匿名才填）
            if (!Boolean.TRUE.equals(r.getAnonymity())) {
                UserDTO asker = userMap.get(r.getUserId());
                if (asker != null) {
                    vo.setUserName(asker.getName());
                    vo.setUserIcon(asker.getIcon());
                }
            }

            // 6.3 最新回复信息
            InteractionReply reply = replyMap.get(r.getLatestAnswerId());
            if (reply != null) {
                vo.setLatestReplyContent(reply.getContent());

                // 最新回复人（非匿名才填）
                if (!Boolean.TRUE.equals(reply.getAnonymity())) {
                    UserDTO replier = userMap.get(reply.getUserId());
                    if (replier != null) { // 这里加判空更稳
                        vo.setLatestReplyUser(replier.getName());
                    }
                }
            }

            voList.add(vo);
        }

        // 7) 返回分页结果
        return PageDTO.of(page, voList);
    }




    @Override
    public QuestionVO queryQuestionById(Long id) {
        // 1.根据id查询数据
        InteractionQuestion question = getById(id);
        // 2.数据校验
        if(question == null || question.getHidden()){
            // 没有数据或者是被隐藏了
            return null;
        }
        // 3.查询提问者信息
        UserDTO user = null;
        if(!question.getAnonymity()){
            user = userClient.queryUserById(question.getUserId());
        }
        // 4.封装VO
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        return vo;

    }

    /**
     * queryQuestionPageAdmin 是“后台问题管理列表”的分页查询方法：
     * 先根据课程名关键字通过搜索服务转换出课程 ID 列表，再结合状态、时间区间拼出查询条件从 interaction_question 表里分页查问题；
     * 然后批量查询提问者、课程、章节/小节信息，避免 N+1 查询；
     * 最后将这些信息组装成 QuestionAdminVO 列表并配上分页信息，返回给前端用于渲染问题管理页面。
     * @param query
     * @return
     */
    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        // 1.处理课程名称，得到课程id
        List<Long> courseIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())) {
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if (CollUtils.isEmpty(courseIds)) {
                return PageDTO.empty(0L, 0L);
            }
        }
// 2.分页查询
        Integer status = query.getStatus();
        LocalDateTime begin = query.getBeginTime();
        LocalDateTime end = query.getEndTime();
        Page<InteractionQuestion> page = lambdaQuery()
                .in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(begin != null, InteractionQuestion::getCreateTime, begin)
                .lt(end != null, InteractionQuestion::getCreateTime, end)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

// 得到分页的纪录
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

// 3.准备VO需要的数据：用户数据、课程数据、章节数据
        Set<Long> userIds = new HashSet<>();
        Set<Long> cIds = new HashSet<>();
        Set<Long> cataIds = new HashSet<>();
// 3.1.获取各种数据的id集合
        for (InteractionQuestion q : records) {
            userIds.add(q.getUserId());
            cIds.add(q.getCourseId());
            cataIds.add(q.getChapterId());
            cataIds.add(q.getSectionId());
        }
// 3.2.根据id查询用户
// userClient 调用用户服务的接口，批量查询用户信息
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = new HashMap<>(users.size());
        if (CollUtils.isNotEmpty(users)) {
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

// 3.3.根据id查询课程
        List<CourseSimpleInfoDTO> cInfos = courseClient.getSimpleInfoList(cIds);
        Map<Long, CourseSimpleInfoDTO> cInfoMap = new HashMap<>(cInfos.size());
        if (CollUtils.isNotEmpty(cInfos)) {
            cInfoMap = cInfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        }

// 3.4.根据id查询章节
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cataIds);
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)) {
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }


// 4.封装VO
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            // 4.1.将PO转VO，属性拷贝
            QuestionAdminVO vo = BeanUtils.copyBean(q, QuestionAdminVO.class);
            voList.add(vo);
            // 4.2.用户信息
            UserDTO user = userMap.get(q.getUserId());
            if (user != null) {
                vo.setUserName(user.getName());
            }
            // 4.3.课程信息以及分类信息
            CourseSimpleInfoDTO cInfo = cInfoMap.get(q.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());
                vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            }
            // 4.4.章节信息
            vo.setChapterName(cataMap.getOrDefault(q.getChapterId(), ""));
            vo.setSectionName(cataMap.getOrDefault(q.getSectionId(), ""));
        }
        return PageDTO.of(page, voList);

    }

    /**
     * queryQuestionByIdAdmin(Long id) 用于后台管理员查看问题详情。
     * 它首先从问题表查出问题实体，并拷贝成 VO；
     * 然后通过用户服务获取提问者昵称和头像；
     * 再通过课程服务获取课程名称、分类名称和授课老师列表；
     * 最后通过目录服务查询问题所属的章节和小节名称，全部写入 QuestionAdminVO 返回。
     * @param id
     * @return
     */
    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1.根据id查询问题
        InteractionQuestion question = getById(id);
        if (question == null) {
            return null;
        }
        // 2.转PO为VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);
        // 3.查询提问者信息
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        // 4.查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(
                question.getCourseId(), false, true);
        if (cInfo != null) {
            // 4.1.课程名称信息
            vo.setCourseName(cInfo.getName());
            // 4.2.分类信息
            vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            // 4.3.教师信息
            List<Long> teacherIds = cInfo.getTeacherIds();
            List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
            if(CollUtils.isNotEmpty(teachers)) {
                vo.setTeacherName(teachers.stream()
                        .map(UserDTO::getName).collect(Collectors.joining("/")));
            }
        }
        // 5.查询章节信息
        List<CataSimpleInfoDTO> catalogue = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId()));
        Map<Long, String> cataMap = new HashMap<>(catalogue.size());
        if (CollUtils.isNotEmpty(catalogue)) {
            cataMap = catalogue.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        vo.setChapterName(cataMap.getOrDefault(question.getChapterId(), ""));
        vo.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));
        // 6.封装VO
        return vo;

    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        // 1.更新问题
        InteractionQuestion question = new InteractionQuestion();
        question.setId(id);
        question.setHidden(hidden);
        updateById(question);
    }

    @Override
    public void updateQuestion(Long id, QuestionFormDTO questionDTO) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.查询当前问题
        InteractionQuestion q = getById(id);
        if (q == null) {
            throw new BadRequestException("问题不存在");
        }
        // 3.判断是否是当前用户的问题
        if (!q.getUserId().equals(userId)) {
            // 不是，抛出异常
            throw new BadRequestException("无权修改他人的问题");
        }
        // 4.修改问题
        InteractionQuestion question = BeanUtils.toBean(questionDTO, InteractionQuestion.class);
        question.setId(id);
        updateById(question);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.查询当前问题
        InteractionQuestion q = getById(id);
        if (q == null) {
            return;
        }
        // 3.判断是否是当前用户的问题
        if (!q.getUserId().equals(userId)) {
            // 不是，抛出异常
            throw new BadRequestException("无权删除他人的问题");
        }
        // 4.删除问题
        removeById(id);
        // 5.删除答案
        replyMapper.delete(
                new QueryWrapper<InteractionReply>().lambda().eq(InteractionReply::getQuestionId, id)
        );
    }
}
