package dev.eyf.netherlink.neoforge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.server.MinecraftServer;

/**
 * 服务端主线程执行器。
 *
 * <p>直接用 {@link MinecraftServer#execute}——它就是原版给「把任务排到服务端主线程」
 * 用的入口，与加载器无关（Fabric / NeoForge 上是同一个方法）。
 *
 * <p>操作世界 / 玩家 / 广播**必须在主线程**上；纯网络 IO 不必（{@link #executeAsync}）。
 */
public final class MainThreadExecutor implements Executor {

    /** 由 {@link NetherLinkNeoForge} 在服务端启动时填。启动前为 null。 */
    private volatile MinecraftServer server;

    void bind(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void execute(Runnable r) {
        MinecraftServer s = server;
        if (s != null && s.isRunning()) {
            s.execute(r);
        } else {
            // 服务端还没起来（或已在关停）——直接跑，别把消息丢掉。
            // 这个窗口里通常只有 WS 的连接回调，本身不碰世界状态，是安全的。
            r.run();
        }
    }

    /** 纯网络 IO：不需要主线程，别占着服务端 tick 线程。 */
    void executeAsync(Runnable r) {
        CompletableFuture.runAsync(r);
    }
}
