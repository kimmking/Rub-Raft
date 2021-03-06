package cc.lovezhy.raft.server.node;

import cc.lovezhy.raft.rpc.common.RpcExecutors;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.io.Closeable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

public class PeerNodeStateMachine implements Closeable {

    public static PeerNodeStateMachine create(Long nextIndex) {
        return new PeerNodeStateMachine(nextIndex);
    }

    private volatile Long nextIndex;
    private volatile Long matchIndex;
    private volatile PeerNodeStatus nodeStatus;
    private LinkedBlockingDeque<Runnable> taskQueue;
    private ListeningExecutorService taskExecutor;
    private ExecutorService schedulerExecutor;

    private Map<Integer, SettableFuture<Boolean>> appendLogIndexCompleteFuture = Maps.newConcurrentMap();
    private volatile Integer maxWaitIndex = 1;
    private volatile boolean shutdown = false;

    private Runnable scheduleTask = () -> {
        for (; ; ) {
            Runnable task = null;
            try {
                task = taskQueue.take();
            } catch (InterruptedException e) {
                // ignore
            }
            if (Objects.isNull(task)) {
                continue;
            }
            if (shutdown) {
                break;
            }
            taskExecutor.submit(task).addListener(() -> {
                SettableFuture<Boolean> voidSettableFuture = appendLogIndexCompleteFuture.get(matchIndex.intValue());
                if (Objects.nonNull(voidSettableFuture) && !voidSettableFuture.isDone()) {
                    appendLogIndexCompleteFuture.remove(matchIndex.intValue());
                    voidSettableFuture.set(true);
                }
            }, RpcExecutors.commonExecutor());
        }
    };

    private PeerNodeStateMachine(Long nextIndex) {
        this.taskQueue = new LinkedBlockingDeque<>(10);
        this.taskExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        this.schedulerExecutor = Executors.newSingleThreadExecutor();
        this.nextIndex = nextIndex;
        this.matchIndex = 0L;
        this.nodeStatus = PeerNodeStatus.NORMAL;
        this.schedulerExecutor.execute(scheduleTask);
    }

    /**
     * 把任务抢占到队列开头，优先执行
     */
    public void appendFirst(Runnable task) {
        taskQueue.addFirst(task);
    }

    public void append(Runnable task) {
        taskQueue.add(task);
    }

    public Long getNextIndex() {
        return nextIndex;
    }

    public void setNextIndex(Long nextIndex) {
        this.nextIndex = nextIndex;
    }

    public Long getMatchIndex() {
        return matchIndex;
    }

    public void setMatchIndex(Long matchIndex) {
        this.matchIndex = matchIndex;
    }

    public PeerNodeStatus getNodeStatus() {
        return nodeStatus;
    }

    public void setNodeStatus(PeerNodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
    }

    public boolean taskQueueIsEmpty() {
        return taskQueue.isEmpty();
    }

    public SettableFuture<Boolean> setCompleteFuture(Integer notifyIndex) {
        if (notifyIndex < maxWaitIndex) {
            SettableFuture<Boolean> settableFuture = SettableFuture.create();
            return settableFuture;
        }
        SettableFuture<Boolean> settableFuture = SettableFuture.create();
        maxWaitIndex = maxWaitIndex > notifyIndex ? maxWaitIndex : notifyIndex;
        appendLogIndexCompleteFuture.put(notifyIndex, settableFuture);
        return settableFuture;
    }

    public boolean needSendAppendLogImmediately() {
        return maxWaitIndex > matchIndex;
    }

    @Override
    public void close() {
        shutdown = true;
        taskQueue.clear();
        taskExecutor.shutdown();
        schedulerExecutor.shutdown();
    }
}
