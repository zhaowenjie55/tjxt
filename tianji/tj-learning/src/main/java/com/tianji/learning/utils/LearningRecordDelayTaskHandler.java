package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;

@Slf4j
@RequiredArgsConstructor
@Component
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();
    private final static String RECORD_KEY_TEMPLATE = "learning:record:{}";
    private final LearningRecordMapper recordMapper;
    private final ILearningLessonService lessonService;
    private static volatile boolean begin = true;

    @SneakyThrows
    @PostConstruct
    public void init(){
        CompletableFuture.runAsync(this::handleDelayTask);
    }
    @PreDestroy
    public void destroy(){
        log.debug("关闭学习记录处理的延迟任务");
        begin = false;
    }
    private void handleDelayTask() throws InterruptedException {
        while (begin) {
            try {
                DelayTask<RecordTaskData> task = queue.take();
                log.debug("处理学习记录的延迟任务，当前时间：{}", LocalDateTime.now());
                RecordTaskData data = task.getData();

                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());

                if (record == null) {
                    continue;
                }

                if (! Objects.equals(data.getMoment(), record.getMoment())) {
                    continue;
                }

                record.setFinished(null);
                recordMapper.updateById(record);

                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());
                lesson.setLatestLearnTime(LocalDateTime.now());
                lesson.setLatestSectionId(data.getSectionId());
                lessonService.updateById(lesson);

                log.debug("学习记录的延迟任务处理完成");

            } catch (Exception e) {
                log.error("处理学习记录的延迟任务异常", e);
            }

        }



    }

    public void addLearningRecordTask(LearningRecord record){
        writeRecordCache(record);
        queue.add(new DelayTask<>(new RecordTaskData(record), Duration.ofSeconds(20)));

    }

    public void writeRecordCache(LearningRecord record) {
        log.debug("写入学习记录缓存，当前时间：{}", LocalDateTime.now());
        try {
            String jsonStr = JsonUtils.toJsonStr(new RecordCacheData(record));
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId(), record.getSectionId());
            redisTemplate.opsForHash().put(key, record.getSectionId(), jsonStr);
            redisTemplate.expire(key, Duration.ofMinutes(1));

        } catch (Exception e) {
            log.error("缓存写入异常", e);
        }
    }

    public LearningRecord readRecordCache(Long lessonId, Long sectionId){
        try {
            // 1.读取Redis数据
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {
                return null;
            }
            // 2.数据检查和转换
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            log.error("缓存读取异常", e);
            return null;
        }
    }

    public void cleanRecordCache(Long lessonId, Long sectionId){
        // 删除数据
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }

    @Data
    @NoArgsConstructor
    private static class RecordCacheData{
        private Long id;
        private Integer moment;
        private Boolean finished;

        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }
    @Data
    @NoArgsConstructor
    private static class RecordTaskData{
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}

