package android.os;
// Runs posted work when the harness drains the queue, on its main thread.
public class Handler {
    public static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public boolean post(Runnable r) { queue.add(r); return true; }
    public static void drain() { Runnable r; while ((r = queue.poll()) != null) r.run(); }
}
