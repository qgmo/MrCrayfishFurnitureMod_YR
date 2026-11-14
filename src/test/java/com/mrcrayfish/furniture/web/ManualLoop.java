package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.stack.EventLoop;

import java.util.PriorityQueue;

/** 测试用的事件循环：时间手动拨，任务全在测试线程上跑 */
final class ManualLoop implements EventLoop
{
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long now;
    private long seq;

    private final class Task implements Comparable<Task>, Timer
    {
        final long at;
        final long order = seq++;
        final Runnable r;
        boolean cancelled;

        Task(long at, Runnable r)
        {
            this.at = at;
            this.r = r;
        }

        @Override
        public int compareTo(Task o)
        {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order);
        }

        @Override
        public void cancel()
        {
            cancelled = true;
        }
    }

    @Override
    public void execute(Runnable task)
    {
        queue.add(new Task(now, task));
    }

    @Override
    public Timer schedule(Runnable task, long delayMillis)
    {
        Task t = new Task(now + Math.max(0, delayMillis), task);
        queue.add(t);
        return t;
    }

    @Override
    public long nowMillis()
    {
        return now;
    }

    @Override
    public boolean inEventLoop()
    {
        return true;
    }

    void advance(long ms)
    {
        long until = now + ms;
        while(!queue.isEmpty() && queue.peek().at <= until)
        {
            Task t = queue.poll();
            now = Math.max(now, t.at);
            if(!t.cancelled)
                t.r.run();
        }
        now = until;
    }
}
