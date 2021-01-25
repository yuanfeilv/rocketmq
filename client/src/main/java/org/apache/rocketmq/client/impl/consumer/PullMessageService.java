/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.client.impl.consumer;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.utils.ThreadUtils;
//todo K2 拉模式消费者服务线程
public class PullMessageService extends ServiceThread {
    private final InternalLogger log = ClientLogger.getLog();
    private final LinkedBlockingQueue<PullRequest> pullRequestQueue = new LinkedBlockingQueue<PullRequest>();
    private final MQClientInstance mQClientFactory;
    private final ScheduledExecutorService scheduledExecutorService = Executors
        .newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "PullMessageServiceScheduledThread");
            }
        });

    public PullMessageService(MQClientInstance mQClientFactory) {
        this.mQClientFactory = mQClientFactory;
    }

    public void executePullRequestLater(final PullRequest pullRequest, final long timeDelay) {
        if (!isStopped()) {
            this.scheduledExecutorService.schedule(new Runnable() {
                @Override
                public void run() {
                    PullMessageService.this.executePullRequestImmediately(pullRequest);
                }
            }, timeDelay, TimeUnit.MILLISECONDS);
        } else {
            log.warn("PullMessageServiceScheduledThread has shutdown");
        }
    }

    public void executePullRequestImmediately(final PullRequest pullRequest) {
        try {
            this.pullRequestQueue.put(pullRequest);
        } catch (InterruptedException e) {
            log.error("executePullRequestImmediately pullRequestQueue.put", e);
        }
    }

    public void executeTaskLater(final Runnable r, final long timeDelay) {
        if (!isStopped()) {
            this.scheduledExecutorService.schedule(r, timeDelay, TimeUnit.MILLISECONDS);
        } else {
            log.warn("PullMessageServiceScheduledThread has shutdown");
        }
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    private void pullMessage(final PullRequest pullRequest) {
        // 根据消费者组获取mqConsumerInner, 这里是因为可能一个实例中注册了多个消费者用于消费不同的消息或者有不同的队列
        final MQConsumerInner consumer = this.mQClientFactory.selectConsumer(pullRequest.getConsumerGroup());
        if (consumer != null) {
            DefaultMQPushConsumerImpl impl = (DefaultMQPushConsumerImpl) consumer;
            //todo K2 推模式的消费者最终还是会使用拉消息的方式
            impl.pullMessage(pullRequest);
        } else {
            log.warn("No matched consumer for the PullRequest {}, drop it", pullRequest);
        }
    }

    @Override
    public void run() {
        log.info(this.getServiceName() + " service started");

        // 判断当前是否停止
        while (!this.isStopped()) {
            try {
                //拉取消息的请求队列 todo 这个 queue 从哪里构造request?
                PullRequest pullRequest = this.pullRequestQueue.take();
                //处理请求
                this.pullMessage(pullRequest);
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                log.error("Pull Message Service Run Method exception", e);
            }
        }

        log.info(this.getServiceName() + " service end");
    }

    @Override
    public void shutdown(boolean interrupt) {
        super.shutdown(interrupt);
        ThreadUtils.shutdownGracefully(this.scheduledExecutorService, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public String getServiceName() {
        return PullMessageService.class.getSimpleName();
    }

}
