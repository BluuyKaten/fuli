package com.fuli.data.tushare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 同步进度管理器
 * 负责管理同步任务的进度存储和 SSE 推送
 */
@Slf4j
@Component
public class SyncProgressManager {

    /** 存储所有同步任务的进度,key 为 taskId */
    private final Map<String, SyncProgress> progressMap = new ConcurrentHashMap<>();

    /** 存储 SSE 监听器,key 为 taskId */
    private final Map<String, List<SseEmitter>> emittersMap = new ConcurrentHashMap<>();

    /**
     * 创建同步任务
     *
     * @param taskId    任务ID
     * @param startDate 起始日期
     * @param endDate   结束日期
     * @param totalDays 总天数
     * @return 创建的进度对象
     */
    public SyncProgress createTask(String taskId, String startDate, String endDate, int totalDays) {
        SyncProgress progress = new SyncProgress();
        progress.setTaskId(taskId);
        progress.setStartDate(startDate);
        progress.setEndDate(endDate);
        progress.setTotalDays(totalDays);
        progress.setStatus(SyncProgress.SyncStatus.PENDING);
        progress.setMessage("任务创建中...");
        progress.calculatePercent();

        progressMap.put(taskId, progress);
        return progress;
    }

    /**
     * 获取任务进度
     */
    public SyncProgress getProgress(String taskId) {
        return progressMap.get(taskId);
    }

    /**
     * 更新进度(自动推送)
     *
     * @param taskId   任务ID
     * @param updater 更新逻辑
     */
    public void updateProgress(String taskId, Consumer<SyncProgress> updater) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return;
        }
        updater.accept(progress);
        progress.calculatePercent();
        pushProgress(taskId, progress);
    }

    /**
     * 标记任务完成(带完成消息)
     */
    public void completeTask(String taskId, String message) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return;
        }
        progress.setStatus(SyncProgress.SyncStatus.COMPLETED);
        progress.setProcessedDays(progress.getTotalDays());
        progress.calculatePercent();
        progress.setEndTime(LocalDateTime.now());
        progress.setMessage(message != null ? message : "同步完成");
        pushProgress(taskId, progress);
        // 延迟清理,让前端有时间收到最后一条消息
        scheduleCleanup(taskId);
    }

    /**
     * 更新任务状态
     */
    public void updateTaskStatus(String taskId, SyncProgress.SyncStatus status, String message) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return;
        }
        progress.setStatus(status);
        if (message != null) {
            progress.setMessage(message);
        }
        pushProgress(taskId, progress);
    }

    /**
     * 标记任务失败
     */
    public void failTask(String taskId, String errorMessage, String errorDetail) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return;
        }
        progress.setStatus(SyncProgress.SyncStatus.FAILED);
        progress.setMessage(errorMessage);
        progress.setErrorDetail(errorDetail);
        progress.setEndTime(LocalDateTime.now());
        pushProgress(taskId, progress);
        scheduleCleanup(taskId);
    }

    /**
     * 注册 SSE 监听器
     */
    public SseEmitter subscribe(String taskId) {
        SyncProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return null;
        }

        // 创建 SSE 连接,超时时间 30 分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emittersMap.computeIfAbsent(taskId, k -> new ArrayList<>()).add(emitter);

        // 连接关闭时清理
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(e -> removeEmitter(taskId, emitter));

        // 立即推送当前进度
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(progress));
        } catch (IOException e) {
            log.warn("推送初始进度失败: {}", e.getMessage());
        }

        // 如果任务已结束,直接关闭连接
        if (progress.getStatus() == SyncProgress.SyncStatus.COMPLETED
                || progress.getStatus() == SyncProgress.SyncStatus.FAILED) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("关闭 SSE 连接失败: {}", e.getMessage());
            }
        }

        return emitter;
    }

    /**
     * 推送进度到所有监听器
     */
    private void pushProgress(String taskId, SyncProgress progress) {
        List<SseEmitter> emitters = emittersMap.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(progress));
            } catch (IOException e) {
                log.warn("推送进度失败,移除监听器: {}", e.getMessage());
                deadEmitters.add(emitter);
            }
        }

        // 移除失效的连接
        for (SseEmitter dead : deadEmitters) {
            removeEmitter(taskId, dead);
        }
    }

    /**
     * 移除失效的监听器
     */
    private void removeEmitter(String taskId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersMap.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersMap.remove(taskId);
            }
        }
    }

    /**
     * 延迟清理任务数据(5 分钟后)
     */
    private void scheduleCleanup(String taskId) {
        new Thread(() -> {
            try {
                Thread.sleep(5 * 60 * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            progressMap.remove(taskId);
            // 关闭所有 SSE 连接
            List<SseEmitter> emitters = emittersMap.remove(taskId);
            if (emitters != null) {
                for (SseEmitter emitter : emitters) {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            log.info("清理同步任务数据: {}", taskId);
        }).start();
    }
}
