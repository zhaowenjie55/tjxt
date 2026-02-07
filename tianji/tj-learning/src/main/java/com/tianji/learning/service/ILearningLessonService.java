package com.tianji.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;

import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2022-12-02
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    void addUserLessons(Long userId, List<Long> courseIds);

    LearningLessonVO queryMyLessons(PageQuery query);

    LearningLessonVO queryMyCurrentLesson();

    /*
    queryMyCurrentLesson() 用来查询当前登录用户正在学习的课程：
    先从 learning_lesson 表中按 user_id 和 status=LEARNING 查出最近学习的一门课；
    然后把课表 PO 拷贝到 VO，并通过课程服务补充课程名称、封面和总小节数；
    再统计该用户在课表中的课程总数，写入 courseAmount；
    最后调用目录服务，查询最近学习小节的名称和序号，封装到 VO 中一起返回。
     */
    LearningLessonVO queryMycurrentLesson();

    LearningLessonVO queryLessonByCourseId(Long courseId);

    void deleteCourseFromLesson(Long userId, Long courseId);

    Integer countLearningLessonByCourse(Long courseId);

    Long isLessonValid(Long courseId);

    LearningLesson queryByUserAndCourseId(Long userId, Long courseId);

    void createLearningPlan(Long courseId, Integer freq);

    LearningPlanPageVO queryMyPlans(PageQuery query);
}
