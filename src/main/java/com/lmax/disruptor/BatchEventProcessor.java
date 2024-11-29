/*
 * Copyright 2022 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.atomic.AtomicInteger;

import static com.lmax.disruptor.RewindAction.REWIND;
import static java.lang.Math.min;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
        implements EventProcessor
{
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    // 表示当前事件处理器的运行状态
    private final AtomicInteger running = new AtomicInteger(IDLE);
    // 异常处理器
    private ExceptionHandler<? super T> exceptionHandler;
    // 数据提供者(RingBuffer)
    private final DataProvider<T> dataProvider;
    // 序列栅栏
    private final SequenceBarrier sequenceBarrier;
    // 真正处理事件的回调接口
    private final EventHandlerBase<? super T> eventHandler;
    private final int batchLimitOffset;
    // 事件处理器使用的序列
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RewindHandler rewindHandler;
    private int retriesAttempted = 0;

    BatchEventProcessor(
            final DataProvider<T> dataProvider,
            final SequenceBarrier sequenceBarrier,
            final EventHandlerBase<? super T> eventHandler,
            final int maxBatchSize,
            final BatchRewindStrategy batchRewindStrategy
    )
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (maxBatchSize < 1)
        {
            throw new IllegalArgumentException("maxBatchSize must be greater than 0");
        }
        this.batchLimitOffset = maxBatchSize - 1;

        this.rewindHandler = eventHandler instanceof RewindableEventHandler
                ? new TryRewindHandler(batchRewindStrategy)
                : new NoRewindHandler();
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        // 设置运行状态为false
        running.set(HALTED);
        // 通知序列栅栏
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}.
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        int witnessValue = running.compareAndExchange(IDLE, RUNNING);
        if (witnessValue == IDLE) // Successful CAS
        {
            // 先清除序列栅栏的通知状态
            sequenceBarrier.clearAlert();

            // 如果eventHandler实现了LifecycleAware，这里会对其进行一个启动通知
            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                // 主循环退出后，如果eventHandler实现了LifecycleAware，这里会对其进行一个停止通知
                notifyShutdown();
                // 设置事件处理器运行状态为停止
                running.set(IDLE);
            }
        }
        else
        {
            if (witnessValue == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                earlyExit();
            }
        }
    }

    private void processEvents()
    {
        T event = null;
        // 获取要申请的序列值
        long nextSequence = sequence.get() + 1L;

        while (true)
        {
            final long startOfBatchSequence = nextSequence;
            try
            {
                try
                {
                    // 通过序列栅栏来等待可用的序列值
                    // 可能nextSequence =9，而availableSequence=12
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    final long endOfBatchSequence = min(nextSequence + batchLimitOffset, availableSequence);

                    if (nextSequence <= endOfBatchSequence)
                    {
                        eventHandler.onBatchStart(endOfBatchSequence - nextSequence + 1, availableSequence - nextSequence + 1);
                    }

                    // 批量数据进行迭代处理
                    while (nextSequence <= endOfBatchSequence)
                    {
                        // 获取事件
                        event = dataProvider.get(nextSequence);
                        // 将事件交给eventHandler处理
                        eventHandler.onEvent(event, nextSequence, nextSequence == endOfBatchSequence);
                        nextSequence++;
                    }

                    retriesAttempted = 0;

                    // 处理完毕后，设置当前处理完成的最后序列值
                    sequence.set(endOfBatchSequence);
                }
                catch (final RewindableException e)
                {
                    nextSequence = rewindHandler.attemptRewindGetNextSequence(e, startOfBatchSequence);
                }
            }
            catch (final TimeoutException e)
            {
                // 如果发生超时，通知一下超时处理器(如果eventHandler同时实现了timeoutHandler，会将其设置为当前的超时处理器)
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                // 如果捕获了序列栅栏变更通知，并且当前事件处理器停止了，那么退出主循环
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // 其他的异常都交给异常处理器进行处理
                handleEventException(ex, nextSequence, event);
                // 处理异常后仍然会设置当前处理的最后的序列值，然后继续处理其他事件
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            eventHandler.onTimeout(availableSequence);
        }
        catch (Throwable e)
        {
            handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up.
     */
    private void notifyStart()
    {
        try
        {
            eventHandler.onStart();
        }
        catch (final Throwable ex)
        {
            handleOnStartException(ex);
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down.
     */
    private void notifyShutdown()
    {
        try
        {
            eventHandler.onShutdown();
        }
        catch (final Throwable ex)
        {
            handleOnShutdownException(ex);
        }
    }

    /**
     * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleEventException(final Throwable ex, final long sequence, final T event)
    {
        getExceptionHandler().handleEventException(ex, sequence, event);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnStartException(final Throwable ex)
    {
        getExceptionHandler().handleOnStartException(ex);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnShutdownException(final Throwable ex)
    {
        getExceptionHandler().handleOnShutdownException(ex);
    }

    private ExceptionHandler<? super T> getExceptionHandler()
    {
        ExceptionHandler<? super T> handler = exceptionHandler;
        return handler == null ? ExceptionHandlers.defaultHandler() : handler;
    }

    private class TryRewindHandler implements RewindHandler
    {
        private final BatchRewindStrategy batchRewindStrategy;

        TryRewindHandler(final BatchRewindStrategy batchRewindStrategy)
        {
            this.batchRewindStrategy = batchRewindStrategy;
        }

        @Override
        public long attemptRewindGetNextSequence(final RewindableException e, final long startOfBatchSequence) throws RewindableException
        {
            if (batchRewindStrategy.handleRewindException(e, ++retriesAttempted) == REWIND)
            {
                return startOfBatchSequence;
            }
            else
            {
                retriesAttempted = 0;
                throw e;
            }
        }
    }

    private static class NoRewindHandler implements RewindHandler
    {
        @Override
        public long attemptRewindGetNextSequence(final RewindableException e, final long startOfBatchSequence)
        {
            throw new UnsupportedOperationException("Rewindable Exception thrown from a non-rewindable event handler", e);
        }
    }
}