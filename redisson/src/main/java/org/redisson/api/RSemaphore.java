/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.api;

import java.util.concurrent.TimeUnit;

/**
 * Redis based implementation of {@link java.util.concurrent.Semaphore}.
 *
 * <p>Works in non-fair mode. Therefore order of acquiring is unpredictable.
 *
 * @author Nikita Koksharov
 */
public interface RSemaphore extends RExpirable, RSemaphoreAsync {

    /**
     * Acquires a permit.
     * Waits if necessary until a permit became available.
     * 获取一个许可，如果暂时没有可获得的许可，则等待直到有空闲的许可
     *
     * @throws InterruptedException if the current thread was interrupted
     */
    void acquire() throws InterruptedException;

    /**
     * Acquires defined amount of <code>permits</code>.
     * Waits if necessary until all permits became available.
     * <p>
     * 获取一定数量（permits）的许可，上述的 acquire() = acquire(1)
     *
     * @param permits the number of permits to acquire
     * @throws InterruptedException     if the current thread is interrupted
     * @throws IllegalArgumentException if <code>permits</code> is negative
     */
    void acquire(int permits) throws InterruptedException;

    /**
     * Tries to acquire currently available permit.
     *
     * @return <code>true</code> if a permit was acquired and <code>false</code>
     * otherwise
     * <p>
     * 尝试去获取一个许可，
     * 如果成功获取则返回true，
     * 否则返回false
     */
    boolean tryAcquire();

    /**
     * Tries to acquire defined amount of currently available <code>permits</code>.
     *
     * @param permits the number of permits to acquire
     * @return <code>true</code> if permits were acquired and <code>false</code>
     * otherwise
     */
    boolean tryAcquire(int permits);

    /**
     * Tries to acquire currently available permit.
     * Waits up to defined <code>waitTime</code> if necessary until a permit became available.
     * <p>
     * 在设定的时间内尝试去获取一个许可，
     * 成功则返回true
     * 失败则返回false
     *
     * @param waitTime the maximum time to wait
     * @param unit     the time unit
     * @return <code>true</code> if a permit was acquired and <code>false</code>
     * otherwise
     * @throws InterruptedException if the current thread was interrupted
     */
    boolean tryAcquire(long waitTime, TimeUnit unit) throws InterruptedException;

    /**
     * Tries to acquire defined amount of currently available <code>permits</code>.
     * Waits up to defined <code>waitTime</code> if necessary until all permits became available.
     *
     * @param permits  amount of permits
     * @param waitTime the maximum time to wait
     * @param unit     the time unit
     * @return <code>true</code> if permits were acquired and <code>false</code>
     * otherwise
     * @throws InterruptedException if the current thread was interrupted
     */
    boolean tryAcquire(int permits, long waitTime, TimeUnit unit) throws InterruptedException;

    /**
     * Releases a permit. Increases the number of available permits.
     * 释放一个许可
     */
    void release();

    /**
     * Releases defined amount of <code>permits</code>.
     * Increases the number of available permits by <code>permits</code> amount.
     * <p>
     * 释放一定数量（permits个）的许可
     *
     * @param permits amount of permits
     */
    void release(int permits);

    /**
     * Returns amount of available permits.
     * 获取空闲许可的个数
     *
     * @return number of permits
     */
    int availablePermits();

    /**
     * Acquires and returns all permits that are immediately available.
     * 获取此时所有空闲的许可个数，并占用所有的空闲许可
     *
     * @return number of permits
     */
    int drainPermits();

    /**
     * Tries to set number of permits.
     * 设置许可的个数为给定的值
     *
     * @param permits - number of permits
     * @return <code>true</code> if permits has been set successfully,
     * otherwise <code>false</code> if permits were already set.
     */
    boolean trySetPermits(int permits);

    /*
     * Use addPermits instead
     */
    @Deprecated
    void reducePermits(int permits);

    /**
     * Increases or decreases the number of available permits by defined value.
     * 增加或减少一定数量（permits个）的许可
     *
     * @param permits amount of permits to add/remove
     */
    void addPermits(int permits);


}
