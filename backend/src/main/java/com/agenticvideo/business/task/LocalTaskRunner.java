package com.agenticvideo.business.task;

import com.agenticvideo.business.media.MediaProcessing;
import com.agenticvideo.business.media.MediaInspection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Single-instance active executions; the database owns generations and durable outcomes. */
@Service
public class LocalTaskRunner {
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stopping() { stopping=true; }
    private final TaskExecution tasks;
    private final MediaProcessing media;
    private final com.agenticvideo.business.analysis.AnalysisProcessing analysis;
    public LocalTaskRunner(TaskExecution tasks,MediaProcessing media,com.agenticvideo.business.analysis.AnalysisProcessing analysis) { this.tasks=tasks; this.media=media; this.analysis=analysis; }
    public String type(UUID task) {return tasks.read(task).get("type").toString();}
    public boolean active(UUID task) { return active.contains(task); }

    /** Returns false only after a final failure has been persisted, for dead-lettering. */
    public boolean execute(UUID task) {
        if(!active.add(task))return true;
        TaskExecution.LocalRun run=null;
        try {
            String type=tasks.read(task).get("type").toString();
            run=tasks.claimLocal(task);
            if(run==null)return true;
            var current=run;
            if(Set.of("CONTENT_ANALYZE","VISION_ANALYZE","INDEX_BUILD").contains(type)) {
                analysis.execute(type,run,()->stopping || tasks.stopped(current));
                return true;
            }
            var result=media.execute(run.attemptId(),run.input(),tasks.mediaBudget(run),()->stopping || tasks.stopped(current));
            tasks.completeLocal(run,result.type(),result.data());
            return true;
        }catch(InterruptedException cancelled) {
            if(run!=null && "CANCEL_REQUESTED".equals(tasks.read(task).get("status")))tasks.cancelLocal(run);
            else throw new IllegalStateException("Local execution interrupted",cancelled);
            return true;
        }catch(Exception failure) {
            if(run==null)throw new IllegalStateException("Task admission unavailable",failure);
            if(tasks.stopped(run)) {
                if("CANCEL_REQUESTED".equals(tasks.read(task).get("status")))tasks.cancelLocal(run);
                return true;
            }
            boolean model=Set.of("CONTENT_ANALYZE","VISION_ANALYZE","INDEX_BUILD").contains(tasks.read(task).get("type"));
            String code=failure instanceof com.agenticvideo.business.analysis.AnalysisProcessing.PaidUncertain?"MODEL_SUBMISSION_UNCERTAIN":failure instanceof com.agenticvideo.business.analysis.AnalysisProcessing.InvalidResult?"MODEL_RESULT_INVALID":failure instanceof MediaInspection.InvalidMedia invalid?invalid.code:model?"ANALYSIS_EXECUTION_FAILED":"MEDIA_EXECUTION_FAILED";
            String message=failure instanceof MediaInspection.InvalidMedia || failure instanceof com.agenticvideo.business.analysis.AnalysisProcessing.PaidUncertain || failure instanceof com.agenticvideo.business.analysis.AnalysisProcessing.InvalidResult?failure.getMessage():model?"分析执行暂时失败；已成功的模型回执将复用":"媒体执行暂时失败";
            org.slf4j.LoggerFactory.getLogger(LocalTaskRunner.class).warn("Task {} failed with {} ({})",task,code,failure.getClass().getSimpleName());
            return !"FAILED".equals(tasks.failLocal(run,code,message));
        }finally { active.remove(task); }
    }
}
