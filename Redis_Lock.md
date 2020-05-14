# Redisson

```java
public class Redisson implements RedissonClient {
    public RLock getLock(String name) {
        return new RedissonLock(this.connectionManager.getCommandExecutor(), name, this.id);
    }

    public RLock getFairLock(String name) {
        return new RedissonFairLock(this.connectionManager.getCommandExecutor(), name, this.id);
    }

    public RReadWriteLock getReadWriteLock(String name) {
        return new RedissonReadWriteLock(this.connectionManager.getCommandExecutor(), name, this.id);
    }
}
```

# RedissonLock

```java
public class RedissonLock extends RedissonExpirable implements RLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonLock.class);
    // 存储entryName和其过期时间，底层用的netty的PlatformDependent.newConcurrentHashMap（）。 
    private static final ConcurrentMap<String, Timeout> expirationRenewalMap = PlatformDependent.newConcurrentHashMap();
    // 锁默认释放的时间，默认是30*10000，即30秒
    protected long internalLockLeaseTime;
    // 用作客户端的唯一标识
    final UUID id;
    // 订阅者模式，当释放锁的时候，其他客户端能够知道锁已经被释放的消息，并让队列的第一个消费者获取锁，
    // 使用PUB/SUB消息机制的优点：
    // 减少申请锁的等待时间、安全、锁带有超时时间，锁的标识唯一，防止死锁，锁可设计为可重入，避免死锁，
    // 是抽象类PublishSubscribe的具体类
    protected static final LockPubSub PUBSUB = new LockPubSub();
    // 命令执行器，异步执行器
    final CommandAsyncExecutor commandExecutor;
    // 构造函数
    public RedissonLock(CommandAsyncExecutor commandExecutor, String name, UUID id) {
        super(commandExecutor, name);
        this.commandExecutor = commandExecutor;
        this.id = id;
        this.internalLockLeaseTime = commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout();
    }

    public void lock() {
        try {
            this.lockInterruptibly();
        } catch (InterruptedException var2) {
            Thread.currentThread().interrupt();
        }
    }

    public void lockInterruptibly() throws InterruptedException {
        this.lockInterruptibly(-1L, (TimeUnit)null);
    }

    public void lock(long leaseTime, TimeUnit unit) {
        try {
            this.lockInterruptibly(leaseTime, unit);
        } catch (InterruptedException var5) {
            Thread.currentThread().interrupt();
        }
    }

    public void lockInterruptibly(long leaseTime, TimeUnit unit) throws InterruptedException {
        // 获取当前线程ID 
        long threadId = Thread.currentThread().getId();
        // 尝试加锁
        Long ttl = this.tryAcquire(leaseTime, unit, threadId);
        // 如果为空，当前线程获取锁成功，否则一经被其他客户端加锁
        if (ttl != null) {
            RFuture<RedissonLockEntry> future = this.subscribe(threadId);
            this.commandExecutor.syncSubscription(future);

            try {
                while(true) {
                    // 尝试再次加锁，直至成功
                    ttl = this.tryAcquire(leaseTime, unit, threadId);
                    // 成功获取锁，return 退出死循环
                    if (ttl == null) {
                        return;
                    }
                    // 等待锁释放
                    if (ttl >= 0L) {
                        this.getEntry(threadId).getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                    } else {
                        this.getEntry(threadId).getLatch().acquire();
                    }
                }
            } finally {
                // 取消订阅
                this.unsubscribe(future, threadId);
            }
        }
    }

    /**
     *
     */
    protected RFuture<RedissonLockEntry> subscribe(long threadId) {
        return PUBSUB.subscribe(this.getEntryName(), this.getChannelName(), this.commandExecutor.getConnectionManager());
    }


    private Long tryAcquire(long leaseTime, TimeUnit unit, long threadId) {
        return (Long)this.get(this.tryAcquireAsync(leaseTime, unit, threadId));
    }

    protected <V> V get(RFuture<V> future) {
        return this.commandExecutor.get(future);
    }


    private <T> RFuture<Long> tryAcquireAsync(long leaseTime, TimeUnit unit, final long threadId) {
        // 在lock.lock()的时候，已经声明leaseTime为 -1，尝试加锁 
        if (leaseTime != -1L) {
            return this.tryLockInnerAsync(leaseTime, unit, threadId, RedisCommands.EVAL_LONG);
        } else {
            RFuture<Long> ttlRemainingFuture = this.tryLockInnerAsync(this.commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout(), TimeUnit.MILLISECONDS, threadId, RedisCommands.EVAL_LONG);
            // 监听事件，订阅消息
            ttlRemainingFuture.addListener(new FutureListener<Long>() {
                public void operationComplete(Future<Long> future) throws Exception {
                    if (future.isSuccess()) {
                        Long ttlRemaining = (Long)future.getNow();
                        if (ttlRemaining == null) {
                            // 获取新的超时时间
                            RedissonLock.this.scheduleExpirationRenewal(threadId);
                        }

                    }
                }
            });
            // 获取新的超时时间
            return ttlRemainingFuture;
        }
    }

     <T> RFuture<T> tryLockInnerAsync(long leaseTime, TimeUnit unit, long threadId, RedisStrictCommand<T> command) {
         this.internalLockLeaseTime = unit.toMillis(leaseTime);
         /**
          * 通过lua能够保证操作的原子性
          * KEYS[1](getName()) ：需要加锁的key，这里需要是字符串类型。
          * ARGV[1](internalLockLeaseTime) ：锁的超时时间，防止死锁
          * ARGV[2](getLockName(threadId)) ：锁的唯一标识，也就是刚才介绍的 id（UUID.randomUUID()） + “:” + threadId
          */
         return this.commandExecutor.evalWriteAsync(this.getName(), LongCodec.INSTANCE, command, 
             // 检查key是否被占用了，如果没有则设置超时时间和唯一标识，初始化value = 1
             "if (redis.call('exists', KEYS[1]) == 0) then" +
                 "redis.call('hset', KEYS[1], ARGV[2], 1); " +
                 "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                 "return nil; " +
             "end; " +
             // 如果锁冲入，需要判断锁的key是否相等，在相等的情况下，value = value + 1 
             "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
                  "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
                  // 锁冲入重新设置超时时间
                  "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                  "return nil; " +
             "end; " +
             // 返回剩余的过期时间
             "return redis.call('pttl', KEYS[1]);", 
         Collections.singletonList(this.getName()), new Object[]{this.internalLockLeaseTime, this.getLockName(threadId)});
     }


    /**
     *
     */
    private void scheduleExpirationRenewal(final long threadId) {
        if (!expirationRenewalMap.containsKey(this.getEntryName())) {
            Timeout task = this.commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                public void run(Timeout timeout) throws Exception {
                    RFuture<Boolean> future = RedissonLock.this.commandExecutor.evalWriteAsync(RedissonLock.this.getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN, "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then redis.call('pexpire', KEYS[1], ARGV[1]); return 1; end; return 0;", Collections.singletonList(RedissonLock.this.getName()), new Object[]{RedissonLock.this.internalLockLeaseTime, RedissonLock.this.getLockName(threadId)});
                    future.addListener(new FutureListener<Boolean>() {
                        public void operationComplete(Future<Boolean> future) throws Exception {
                            RedissonLock.expirationRenewalMap.remove(RedissonLock.this.getEntryName());
                            if (!future.isSuccess()) {
                                RedissonLock.log.error("Can't update lock " + RedissonLock.this.getName() + " expiration", future.cause());
                            } else {
                                if ((Boolean)future.getNow()) {
                                    RedissonLock.this.scheduleExpirationRenewal(threadId);
                                }

                            }
                        }
                    });
                }
            }, this.internalLockLeaseTime / 3L, TimeUnit.MILLISECONDS);
            if (expirationRenewalMap.putIfAbsent(this.getEntryName(), task) != null) {
                task.cancel();
            }
        }
    }




}
```

# PublishSubscribe

```java
abstract class PublishSubscribe<E extends PubSubEntry<E>> {

    public RFuture<E> subscribe(final String entryName, final String channelName, final ConnectionManager connectionManager) {
            final AtomicReference<Runnable> listenerHolder = new AtomicReference();
            final AsyncSemaphore semaphore = connectionManager.getSemaphore(channelName);
            final RPromise<E> newPromise = new RedissonPromise<E>() {
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return semaphore.remove((Runnable)listenerHolder.get());
                }
            };
            Runnable listener = new Runnable() {
                public void run() {
                    E entry = (PubSubEntry)PublishSubscribe.this.entries.get(entryName);
                    if (entry != null) {
                        entry.aquire();
                        semaphore.release();
                        entry.getPromise().addListener(new TransferListener(newPromise));
                    } else {
                        E value = PublishSubscribe.this.createEntry(newPromise);
                        value.aquire();
                        E oldValue = (PubSubEntry)PublishSubscribe.this.entries.putIfAbsent(entryName, value);
                        if (oldValue != null) {
                            oldValue.aquire();
                            semaphore.release();
                            oldValue.getPromise().addListener(new TransferListener(newPromise));
                        } else {
                            RedisPubSubListener<Object> listener = PublishSubscribe.this.createListener(channelName, value);
                            connectionManager.subscribe(LongCodec.INSTANCE, channelName, semaphore, new RedisPubSubListener[]{listener});
                        }
                    }
                }
            };
            semaphore.acquire(listener);
            listenerHolder.set(listener);
            return newPromise;
        }
}
```

# CommandAsyncService
```java
public class CommandAsyncService implements CommandAsyncExecutor {
    public <V> V get(RFuture<V> future) {
        if (!future.isDone()) {
            // 通过CountDownLatch来实现锁释放的监听 
            final CountDownLatch l = new CountDownLatch(1);
            future.addListener(new FutureListener<V>() {
                public void operationComplete(Future<V> future) throws Exception {
                    l.countDown();
                }
            });
            boolean interrupted = false;

            while(!future.isDone()) {
                try {
                    l.await();
                } catch (InterruptedException var5) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (future.isSuccess()) {
            return future.getNow();
        } else {
            throw this.convertException(future);
        }
    }
}
```

# MasterSlaveConnectionManager
```java
public class MasterSlaveConnectionManager implements ConnectionManager {
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        try {
            return this.timer.newTimeout(task, delay, unit);
        } catch (IllegalStateException var6) {
            return this.dummyTimeout;
        }
    }
}
```