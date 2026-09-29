package dev.eyf.netherlink.neoforge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NetherLink MC 端（NeoForge 模组）。
 *
 * <p>职责与 Paper 端（{@code netherlink-plugin}）、Fabric 端（{@code netherlink-fabric}）
 * <b>完全一致</b>，协议也相同，三者接同一个 AstrBot 插件、服务端不用改配置：
 * <ol>
 *   <li>维护与 AstrBot 的 WebSocket 连接（见 {@link AstrBotWsClient}）</li>
 *   <li>MC → QQ：聊天 / 进服 / 退服 / 死亡 / 成就上报；唤醒词开头的聊天作为 bot_chat 上报</li>
 *   <li>QQ → MC：{@code chat} / {@code bot_reply} 下行整行文本广播到公屏；
 *       {@code command} 以控制台身份执行并回传输出</li>
 * </ol>
 *
 * <p>防循环由 AstrBot 侧通过 OneBot self_id 识别机器人自身消息，本端不做文本匹配。
 *
 * <p>⚠️ <b>与 Fabric 端的结构性差异只有事件注册这一块</b>：
 * <ul>
 *   <li>Fabric 是<b>静态事件总线</b>（{@code ServerMessageEvents.CHAT_MESSAGE.register(...)}），
 *       NeoForge 是<b>实例 + {@code @SubscribeEvent}</b> 注解，
 *       由 {@code NeoForge.EVENT_BUS.register(this)} 注册；</li>
 *   <li>Fabric <b>没有</b>「玩家获得成就」事件，只能用 mixin 注入
 *       {@code PlayerAdvancements.award}；NeoForge <b>有</b>
 *       {@link AdvancementEvent.AdvancementEarnEvent}——真正的「获得成就」，
 *       那条 mixin 因此整个不需要了。</li>
 * </ul>
 * 其余（WS 客户端 / 指令捕获 / § 码解析 / 配置）都是<b>加载器无关</b>的，
 * 从 Fabric 端原样搬来，见各自的类注释。
 */
@Mod(NetherLinkNeoForge.MODID)
public class NetherLinkNeoForge {

    public static final String MODID = "netherlink";

    public static final Logger LOGGER = LoggerFactory.getLogger("NetherLink");

    /** 与 AstrBot 握手时上报的服务器标识<b>兜底值</b>；实际取配置 {@code server-name}。
     *  （显示名不在本端控制，由 AstrBot 的 {@code server_display_names} 决定。） */
    public static final String SERVER_NAME = "mc";

    /** 当前实例。事件回调里拿不到 this（NeoForge 反射构造），故留一个静态引用。 */
    public static NetherLinkNeoForge INSTANCE;

    /** 服务端主线程执行器：操作世界/玩家必须在主线程上。 */
    private final MainThreadExecutor mainThread = new MainThreadExecutor();

    private final Gson gson = new Gson();
    private AstrBotWsClient wsClient;
    private MinecraftServer server;

    /** 游戏内机器人唤醒词，从配置读（与 AstrBot 侧 mc_wake_prefixes 一致）。 */
    private java.util.List<String> wakePrefixes = java.util.List.of();

    /** 握手时上报的服务器标识。 */
    private String serverName = SERVER_NAME;

    public NetherLinkNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;
        // ⚠️ 注册到**游戏事件总线**（NeoForge.EVENT_BUS），不是 modEventBus——
        // 聊天 / 进退服 / 死亡 / 成就 / 生命周期都是游戏事件。
        // modEventBus 管的是注册类事件（DeferredRegister 等），本项目一个都不用。
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("NetherLink(NeoForge) 已加载，等待服务端启动");
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 服务端启动完成：此时才能拿到 {@link MinecraftServer}，也才能建连接。
     *
     * <p>⚠️ 用 {@code ServerStartedEvent} 而不是 {@code ServerStartingEvent}——
     * 后者触发时服务端还没跑起来（{@code isRunning()} 为假），
     * 建出来的连接会在第一条下行上踩到「服务器未就绪」。
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        mainThread.bind(server);

        // 配置从文件读（config/netherlink.json），与 Paper 端的 config.yml
        // 键名与默认值逐字对齐——见 NetherLinkConfig 的类注释。
        NetherLinkConfig cfg = NetherLinkConfig.load(FMLPaths.CONFIGDIR.get());

        this.wakePrefixes = parsePrefixes(cfg.wakePrefixesRaw);
        // ⚠️ serverName 必须传**配置里的**，不能传写死的常量 SERVER_NAME：
        // AstrBot 侧在 ws_ports 只填裸端口时，就是靠这个上报名区分服务器
        // （见 main.py 的 `server_id = bound_id or reported`）。传常量的话
        // 多服会全部自称 "mc"，互相顶掉连接。Paper 端一直用的是配置。
        this.serverName = cfg.serverName;

        wsClient = new AstrBotWsClient(LOGGER, mainThread,
                cfg.host, cfg.port, cfg.token, this.serverName);
        wsClient.connect();
        LOGGER.info("NetherLink(NeoForge) 已启用，目标 AstrBot: {}:{}（唤醒词: {}，服务器标识: {}）",
                cfg.host, cfg.port, wakePrefixes, this.serverName);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (wsClient != null) {
            wsClient.shutdown();
        }
        LOGGER.info("NetherLink(NeoForge) 已卸载");
    }

    /**
     * 解析逗号分隔的唤醒词。
     *
     * <p>⚠️ 与 AstrBot 插件侧一样要处理**中文全角分隔符**：中文输入法下打出的
     * 常是全角逗号「，」或顿号「、」，直接 split(",") 会把整串当成一个词。
     * AstrBot 侧统一走 `_SEPARATORS` 归一化，这里做等价的处理。
     */
    static java.util.List<String> parsePrefixes(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String normalized = raw
                .replace('，', ',').replace('、', ',')
                .replace('；', ',').replace(';', ',')
                .replace('　', ',')   // 全角空格
                .replace('：', ':');
        for (String p : normalized.split(",")) {
            String s = p.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 游戏事件
    // ------------------------------------------------------------------

    /** 进服 / 退服。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        report("join", nameOf(event.getEntity()));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        report("leave", nameOf(event.getEntity()));
    }

    /** 聊天：唤醒词开头 → bot_chat，否则普通 chat。 */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String name = event.getUsername();
        // 取**玩家输入的原文**（getRawText），与 Paper / Fabric 端行为一致
        // （那边取的是 AsyncChatEvent 的 message() / signedContent()）。
        // 不用 event.getMessage()——那是**渲染后**的 Component（含队伍前缀等）。
        String text = event.getRawText();

        for (String prefix : wakePrefixes) {
            if (text.startsWith(prefix)) {
                JsonObject o = new JsonObject();
                o.addProperty("type", "bot_chat");
                o.addProperty("player", name);
                o.addProperty("text", text);
                sendAsync(o);
                return;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", name);
        o.addProperty("text", text);
        sendAsync(o);
    }

    /** 死亡。 */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player)) {
            return; // 只报玩家，怪物死亡不上报
        }
        DamageSource source = event.getSource();
        Component deathMessage = source.getLocalizedDeathMessage(player);
        JsonObject o = new JsonObject();
        o.addProperty("type", "death");
        o.addProperty("player", player.getScoreboardName());
        o.addProperty("message", deathMessage.getString());
        sendAsync(o);
    }

    /**
     * 玩家<b>真正获得</b>成就。
     *
     * <p>⚠️ 这是 NeoForge 明显优于 Fabric 的一处：{@code AdvancementEvent} 有两个子类，
     * 语义泾渭分明——
     * <ul>
     *   <li>{@link AdvancementEvent.AdvancementEarnEvent} ← 玩家**真正达成**了成就</li>
     *   <li>{@link AdvancementEvent.AdvancementProgressEvent} ← 某条**判据**的进度变了</li>
     * </ul>
     * Fabric 没有前者，只能用 mixin 注入 {@code PlayerAdvancements.award}，
     * 还得自己过滤「award 返回 false」与「progress.isDone() 为假」——因为
     * {@code award} 对**每个判据**都会调一次，而「获得成就」的语义是**整体完成**。
     * 这里用 Earn 事件，那两条过滤**直接不需要了**。
     *
     * <p>⚠️ 但<b>另外两条过滤仍要保留</b>（与 Paper 端对齐，见下）。
     */
    @SubscribeEvent
    public void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AdvancementHolder holder = event.getAdvancement();
        String key = holder.id().toString(); // 形如 "minecraft:story/mine_diamond"

        // ① 配方解锁不算成就。原版把配方解锁也做成成就（recipes/ 前缀），
        //    不过滤的话合成一次东西就通知 AI 一次。
        // ⚠️ 前缀要连**命名空间**一起判断，不能只看 path。
        if (key.contains(":recipes/") || key.startsWith("recipes/")) {
            return;
        }
        // ② 根成就是分类的**页签标题**（如 "story/root"），它不是玩家感知的成就，
        //    报上去只会让 AI 无意义地加好感。
        //
        //    ⚠️ 判据是「**有没有父成就**」，**不是**「display 是否为空」。
        //    实测（2026-09-28）：`minecraft:story/root` **是有 display 的**
        //    ——那正是成就页签的图标与标题。所以拿 display 判据**根本过滤不掉它**：
        //    探针实测收到了 `advancement='[Minecraft]' key='minecraft:story/root'`。
        //    （Fabric 端代码注释里写的「display 为 absent」是错的，本端不沿用。）
        if (holder.value().parent().isEmpty()) {
            return;
        }

        // 成就的显示名（本地化后的文本）
        String title = Advancement.name(holder).getString();
        reportAdvancement(player.getScoreboardName(), title, key);
    }

    /** 玩家名。{@code getEntity()} 的静态类型是 {@code Player}，不保证是服务端玩家。 */
    private static String nameOf(net.minecraft.world.entity.player.Player player) {
        return player == null ? "" : player.getScoreboardName();
    }

    // ------------------------------------------------------------------
    // 上行发送
    // ------------------------------------------------------------------
    private void report(String type, String player) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("player", player);
        sendAsync(o);
    }

    /** 玩家获得成就时的上报（由 {@link #onAdvancementEarn} 调用）。 */
    public void reportAdvancement(String player, String advancement, String key) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "advancement");
        o.addProperty("player", player);
        o.addProperty("advancement", advancement);
        // key 形如 "minecraft:story/mine_diamond"，与 Paper 端上报的字段一致
        // （那边是 advancement.getKey().getKey()；AstrBot 侧只用它做去重参考）
        o.addProperty("advancement_key", key);
        sendAsync(o);
    }

    /** 网络发送切到 IO 线程，别占着服务端主线程。 */
    private void sendAsync(JsonObject payload) {
        final String json = payload.toString();
        mainThread.executeAsync(() -> {
            AstrBotWsClient c = wsClient;
            if (c == null) {
                LOGGER.warn("WS 客户端未就绪，丢弃消息: {}", json.substring(0, Math.min(60, json.length())));
                return;
            }
            c.send(json);
        });
    }

    // ------------------------------------------------------------------
    // 下行处理（已在主线程）
    // ------------------------------------------------------------------
    public void onWsMessage(String raw) {
        try {
            JsonObject data = gson.fromJson(raw, JsonObject.class);
            String type = data.has("type") ? data.get("type").getAsString() : "";
            switch (type) {
                case "chat", "bot_reply" -> broadcastLine(data);
                case "command" -> runCommand(data);
                default -> {
                    // hello 应答 / 未知类型，忽略
                }
            }
        } catch (Exception e) {
            LOGGER.warn("处理 WS 消息失败: {}", e.getMessage());
        }
    }

    /** 下行整行文本：AstrBot 已渲染好（含 § 染色码），这里解析后广播。 */
    private void broadcastLine(JsonObject data) {
        String line = data.has("line") ? data.get("line").getAsString() : "";
        if (line.isEmpty() || server == null) {
            return;
        }
        Component component = LegacyText.parse(line);
        server.getPlayerList().broadcastSystemMessage(component, false);
    }

    /**
     * 以控制台身份执行一条指令并回传输出。
     *
     * <p>捕获方式是 {@code CommandSourceStack.withSource}：原版指令的反馈最终都会走
     * {@code CommandSource.sendSystemMessage(Component)}，所以把 Source 换成一个
     * 收集器就能拿到输出。权限给 {@code ALL_PERMISSIONS} 以等同于控制台。
     *
     * <p>⚠️ {@code performPrefixedCommand} 返回 **void**（不像 Bukkit 的
     * {@code dispatchCommand} 返回 boolean）。所以「是否执行成功」只能靠
     * **有没有捕获到回调信号**来判断——见 {@link CommandCapture} 的类注释。
     */
    private void runCommand(JsonObject data) {
        String id = data.has("id") ? data.get("id").getAsString() : java.util.UUID.randomUUID().toString();
        String rawCmd = data.has("cmd") ? data.get("cmd").getAsString() : "";
        String cmd = rawCmd.startsWith("/") ? rawCmd.substring(1) : rawCmd;

        if (server == null || cmd.isBlank()) {
            sendCommandResult(id, false, "服务器未就绪或指令为空");
            return;
        }

        CommandCapture capture = new CommandCapture();
        try {
            var source = server.createCommandSourceStack()
                    .withSource(capture)    // ① 收指令输出文本
                    .withCallback(capture)  // ② 收成功/失败信号（文本那路不区分成败）
                    .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            server.getCommands().performPrefixedCommand(source, cmd);
            // 「执行了但无输出」是正常成功（tp / kill / say 都不给执行者反馈），
            // 不能把 output 为空当成失败——成败只看 capture 收到的信号。
            sendCommandResult(id, capture.ok(), capture.text());
        } catch (Exception e) {
            // 抛异常 = 明确失败。必须如实上报，否则 AstrBot 侧会当成功照扣好感
            // （Paper 端踩过：那边曾无条件报 ok=true）。
            sendCommandResult(id, false, "执行异常: " + e.getMessage());
        }
    }

    private void sendCommandResult(String id, boolean ok, String output) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "command_result");
        o.addProperty("id", id);
        // ok 是 AstrBot 侧判断「是否退费」的**唯一**依据：false = 这次执行明确失败，必须退费。
        o.addProperty("ok", ok);
        o.addProperty("output", output);
        sendAsync(o);
    }
}
