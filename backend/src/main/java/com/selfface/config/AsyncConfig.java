package com.selfface.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 简历分析这类「一次请求要等大模型几十秒」的任务，必须挪出 Tomcat 的工作线程。
 * 否则一个用户上传简历就会占住一个请求线程（以及一条数据库连接），并发几个就排队。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 专供大模型调用的线程池。任务本身是纯 IO 等待，所以小池子 + 有限队列即可。
     * 队列满时直接拒绝（AbortPolicy），让调用方立刻收到「排队较多」的提示，
     * 而不是无声堆积到内存里。
     */
    @Bean("resumeExecutor")
    public Executor resumeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("resume-llm-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
