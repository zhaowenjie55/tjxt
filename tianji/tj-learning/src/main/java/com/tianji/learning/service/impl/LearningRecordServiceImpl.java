package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-12-10
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl
        extends ServiceImpl<LearningRecordMapper, LearningRecord>
        implements ILearningRecordService {

    private final ILearningLessonService lessonService;

    private final CourseClient courseClient;

    private final LearningRecordDelayTaskHandler taskHandler;

    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        Long user = UserContext.getUser();
        LearningLesson lesson = lessonService.queryByUserAndCourseId(user, courseId);
        if (lesson == null) {
            throw new BizIllegalException("课表记录不存在！");
        }
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();

        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));
        return dto;
    }

    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.处理学习记录
        boolean finished;
        if (recordDTO.getSectionType() == SectionType.VIDEO) {
            // 2.1.处理视频
            finished = handleVideoRecord(userId, recordDTO);
        } else {
            // 2.2.处理考试
            finished = handleExamRecord(userId, recordDTO);
        }
        if (!finished) {
            // 没有新学完的小节，无需更新课表中的学习进度
            return;
        }
        // 3.处理课表数据
        handleLearningLessonsChanges(recordDTO);
    }

    /**
     * 有小节第一次学完时，更新课表数据（已学小节数、状态等）
     * handleLearningLessonsChanges 用于在某个小节第一次学完时，更新课表 learning_lesson：
     * 它先根据 lessonId 查出课表记录，再通过课程服务获取该课的总小节数；
     * 如果本次完成后“已学习小节数 + 1”已经大于等于总小节数，则标记整门课为已完成；
     * 否则如果这是第一次学习，则把课程状态从“未开始”改为“学习中”；
     * 最后通过 SQL 表达式 learned_sections = learned_sections + 1 对已学习小节数做自增更新，保证并发下统计准确。
     */
    private void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO) {
        // 1.查询课表
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }

        // 2.查询课程数据
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }

        // 3.判断是否全部学完：已学习小节 + 1 >= 课程总小节
        boolean allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();

        // 4.更新课表
        lessonService.lambdaUpdate()
                // 第一次学习，状态从未学习改为学习中
                .set(lesson.getLearnedSections() == 0,
                        LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                // 如果全部学完，状态改为已完成
                .set(allLearned,
                        LearningLesson::getStatus, LessonStatus.FINISHED.getValue())
                // learned_sections = learned_sections + 1
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();


    }

    /**
     * 处理视频类小节的学习记录
     * @return true 表示本次触发了“第一次完成”，需要更新课表
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        if (old == null) {
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            record.setUserId(userId);
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败！");
            }
            return false;
        }
        // 4.存在，则更新
        // 4.1.判断是否是第一次完成
        boolean isFinished = old.getFinished() && recordDTO.getMoment() * 2 >= recordDTO.getDuration();
        if (!isFinished) {
            LearningRecord record = new LearningRecord();
            record.setFinished(old.getFinished());
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            record.setId(record.getId());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        boolean result = this.lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(true, LearningRecord::getFinished, true)
                .set(true, LearningRecord::getFinished, recordDTO.getCommitTime())
                .eq(LearningRecord::getId, old.getId())
                .update();

        if (!result) {
            throw new DbException("更新学习记录失败！");
        }
        taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
        return true;


    }


    /**
     * 从缓存或数据库中查询旧的学习记录
     */
    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        // 1.查询缓存
        LearningRecord record = taskHandler.readRecordCache(lessonId, sectionId);
        // 2.如果命中，直接返回
        if (record != null) {
            return record;
        }
        // 3.未命中，查询数据库
        record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        // 4.写入缓存（可能允许写入 null，用于防止缓存穿透，视实现而定）
        taskHandler.writeRecordCache(record);
        return record;
    }

    /**
     * 处理考试类小节的学习记录
     * 这里逻辑和 handleVideoRecord 类似，目前沿用“moment/duration 决定是否完成”的规则
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.查询旧的学习记录
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        // 2.判断是否存在
        if (old == null) {
            // 3.不存在，则新增
            // 3.1.转换PO
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            // 3.2.填充数据
            record.setUserId(userId);
            // 3.3.写入数据库
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败！");
            }
            return false;
        }

        // 4.存在，则更新
        // 4.1.判断是否是第一次完成
        boolean finished = !old.getFinished()
                && recordDTO.getMoment() * 2 >= recordDTO.getDuration();

        if (!finished) { // 未完成情况，同样只更新进度
            LearningRecord record = new LearningRecord();
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            record.setId(old.getId());
            record.setFinished(old.getFinished());
            // 这里同样移除多余的 “A”
            taskHandler.addLearningRecordTask(record);
            return false;
        }

        // 4.2.完成情况：更新数据库
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(LearningRecord::getFinished, true)
                .set(LearningRecord::getFinishTime, recordDTO.getCommitTime())
                .eq(LearningRecord::getId, old.getId())
                .update();
        if (!success) {
            throw new DbException("更新学习记录失败！");
        }

        // 4.3.清理缓存
        taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
        return true;
    }
}