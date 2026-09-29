package dev.eyf.netherlink.neoforge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

/**
 * WebSocket 客户端：作为客户端主动连入 AstrBot 插件的 WS 服务端。
 *
 * <p>负责连接生命周期（握手鉴权、指数退避重连、心跳）。与 Paper 端（{@code AstrBotWsClient}）
 * 用的是**同一套 JDK API**（{@code java.net.http.WebSocket}），逻辑一一对应，
 * 差别只在调度器：Paper 用 Bukkit 调度器，这里用 JDK 的 {@link ScheduledExecutorService}
 * （NeoForge 是原版环境、没有 Bukkit 调度器，直接用 JDK 的更简单也更可靠）。
 *
 * <p>⚠️ 这个类**原样搬自 Fabric 端**——它是纯 JDK，与模组加载器无关。
 * 那段「指数退避重连 + 15s 心跳 + {@code request(1)} 补额度」是踩坑踩出来的，
 * 换加载器不需要改一行。
 */
public final class AstrBotWsClient implements WebSocket.Listener {

    private final Logger logger;
    private final URI uri;
    private final String token;
    /**
     * 握手时上报的服务器标识。
     *
     * <p>⚠️ **必须从配置传入，不能用 {@code NetherLinkNeoForge.SERVER_NAME} 那个常量。**
     * 这条是**实机测出来的**——第一版照搬 Fabric 时握手指的是常量，于是服务端日志
     * 明明打了「服务器标识: neotest」，探针收到的却是 {@code server_name='mc'}。
     * （Fabric 端有同一个 bug，已一并修掉。）
     *
     * <p>危害在多服：AstrBot 侧 {@code ws_ports} 只填**裸端口**时，正是靠这个上报名
     * 区分服务器（`server_id = bound_id or reported`）——全部自称 `mc` 就会被当成
     * 同一台的重连，两台服互相顶掉、永不停止地互踢。
     */
    private final String serverName;
    private final HttpClient http;
    /** 回调进游戏主线程用（操作世界/玩家必须在服务端线程上）。 */
    private final MainThreadExecutor mainThread;
    /** 收消息与心跳的调度器。单线程足够：收发都是轻量操作。 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NetherLink-WS");
                t.setDaemon(true);
                return t;
            });

    private volatile WebSocket socket;
    private volatile boolean shuttingDown = false;
    private volatile int retryDelay = 3; // 秒，指数退避：3 -> 6 -> 12 -> 24 -> 48（上限 60）

    public AstrBotWsClient(Logger logger, MainThreadExecutor mainThread,
                           String host, int port, String token, String serverName) {
        this.logger = logger;
        this.mainThread = mainThread;
        this.token = token;
        this.serverName = serverName;
        this.uri = URI.create("ws://" + host + ":" + port + "/ws");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConnected() {
        WebSocket s = socket;
        return s != null && !s.isOutputClosed();
    }

    /** 发送一行 JSON 到 AstrBot；未连接时返回 false。 */
    public boolean send(String json) {
        WebSocket s = socket;
        if (s == null || s.isOutputClosed()) {
            return false;
        }
        s.sendText(json, true);
        return true;
    }

    /** 建立连接并开始后台重连循环（异步）。 */
    public void connect() {
        shuttingDown = false;
        attemptConnect();
    }

    private void attemptConnect() {
        if (shuttingDown) {
            return;
        }
        logger.info("正在连接 AstrBot: {}", uri);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        logger.warn("连接 AstrBot 失败: {}", err.getMessage());
                        scheduleReconnect();
                        return;
                    }
                    if (shuttingDown) {
                        // 卸载期间才完成的连接：绝不能存进字段——shutdown() 早就跑完了，
                        // 那个 socket 会永远没人关（send/isConnected 也都读不到它）。
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
                        return;
                    }
                    // 必须存下来：send()/isConnected()/shutdown() 读的都是这个字段，
                    // 漏赋值时它们全都静默失效（send 恒 false），MC→QQ 整条方向静默死掉。
                    socket = ws;
                    retryDelay = 3; // 连上后重置退避
                    // 握手：token 校验由 AstrBot 侧完成，失败会被对方关闭
                    ws.sendText("{\"type\":\"hello\",\"token\":\"" + token
                            + "\",\"server_name\":\"" + serverName + "\"}", true);
                    logger.info("已连接 AstrBot，握手已发送");
                    startHeartbeat();
                });
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        int delay = retryDelay;
        retryDelay = Math.min(retryDelay * 2, 60);
        // 这里用**秒**，不像 Paper 端要乘 20/50 换算 tick 或毫秒——
        // JDK 的调度器直接吃时间单位，不存在 tick 这个中间层。
        scheduler.schedule(this::attemptConnect, delay, TimeUnit.SECONDS);
    }

    /** 每 15 秒发送心跳。 */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!shuttingDown && isConnected()) {
                    send("{\"type\":\"heartbeat\"}");
                }
            } catch (Exception e) {
                logger.warn("心跳发送失败: {}", e.getMessage());
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public void shutdown() {
        shuttingDown = true;
        WebSocket s = socket;
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                logger.warn("关闭连接失败（忽略）: {}", e.getMessage());
            }
        }
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener 回调（在 HttpClient 的线程上触发）
    // ------------------------------------------------------------------
    @Override
    public void onOpen(WebSocket ws) {
        // 建连后的第一次「拉取」。JDK 的默认实现就是 webSocket.request(1)，
        // 显式写出来是为了让「接收是按需拉取」这个契约在代码里可见。
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        String msg = data.toString();
        // 解析 + 后续操作都切回服务端主线程
        mainThread.execute(() -> NetherLinkNeoForge.INSTANCE.onWsMessage(msg));
        // 必须补回这一次 request(1)：WebSocket 的接收是「按需拉取」的——每次派发前
        // 预扣一次额度，回调里不再 request 就永久停止派发。JDK 的默认实现正是
        // `webSocket.request(1); return null;`，覆写 onText 却不 request，
        // 等于读完第一条消息就把读侧关死：表现为握手成功、首条下行能收到，
        // 之后永久静默且不报任何错（Paper 端踩过同一个坑）。
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        logger.warn("与 AstrBot 的连接关闭: {} {}", statusCode, reason);
        if (socket == ws) {
            socket = null;
        }
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        logger.warn("WS 错误: {}", error.getMessage());
    }
}
