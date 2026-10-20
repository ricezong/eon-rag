package cn.kong.eon.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/** 异步线程池配置：摄入与检索分池 */
@Configuration
public class AsyncExecutorConfig {

    /** 文档摄入与手动索引：解析/分块或 embedding，IO 密集且单任务耗时长 */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor(
            @Value("${kb.thread-pool.ingest.core:2}") int core,
            @Value("${kb.thread-pool.ingest.max:4}") int max,
            @Value("${kb.thread-pool.ingest.queue:50}") int queue) {
        return build("eon-rag-ingest-", core, max, queue);
    }

    /** 检索三路并行：IO 密集，单任务耗时短 */
    @Bean("searchExecutor")
    public ThreadPoolTaskExecutor searchExecutor(
            @Value("${kb.thread-pool.search.core:8}") int core,
            @Value("${kb.thread-pool.search.max:16}") int max,
            @Value("${kb.thread-pool.search.queue:100}") int queue) {
        return build("eon-rag-search-", core, max, queue);
    }

    /** 问答超时看门狗：只承载延迟任务，单线程即可 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eon-rag-chat-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    private ThreadPoolTaskExecutor build(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        // 队列满时回落到调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
