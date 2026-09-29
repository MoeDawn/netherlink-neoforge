package dev.eyf.netherlink.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 配置：{@code config/netherlink.json}。
 *
 * <p><b>键名与默认值刻意与 Paper 端（{@code netherlink-plugin}）逐字对齐</b>，
 * 换端时配置可以直接照搬，不必重新理解一遍。三个 MC 端共用同一份配置格式。
 *
 * <p>⚠️ <b>为什么是 JSON 不是 YAML</b>：Paper 端用 {@code config.yml}
 * （Bukkit 自带 YAML 支持），而 NeoForge 侧**既没有 Bukkit 也没有内置 YAML**。
 * 要读 YAML 就得自带 snakeyaml 并 shade 进 jar，多一个依赖和打包环节，
 * 收益只是「后缀好看」。改用 MC 本来就带的 Gson（NeoForge 的依赖里就有），
 * 零新依赖、零打包风险。**格式不同，语义与键名相同。**
 *
 * <p>坏配置一律回退默认值并记 warning，绝不因为配置写错而崩掉服务端。
 *
 * <p>⚠️ 这个类**原样搬自 Fabric 端**——纯 Gson + JDK，与模组加载器无关。
 */
public final class NetherLinkConfig {

    // ---- 默认值：与 Paper 端逐字对齐 ----
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;
    public static final String DEFAULT_TOKEN = "change-me";
    public static final String DEFAULT_SERVER_NAME = "mc";
    public static final String DEFAULT_WAKE_PREFIXES = "ai,助手";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public final String host;
    public final int port;
    public final String token;
    public final String serverName;
    public final String wakePrefixesRaw;

    private NetherLinkConfig(String host, int port, String token,
                             String serverName, String wakePrefixesRaw) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.serverName = serverName;
        this.wakePrefixesRaw = wakePrefixesRaw;
    }

    /**
     * 加载配置；文件不存在时**写出一份带注释说明的默认配置**再返回。
     *
     * <p>与 Paper 端的 {@code saveDefaultConfig()} 行为一致：首次运行自动生成，
     * 用户改完重启即可生效。
     */
    public static NetherLinkConfig load(Path configDir) {
        Path file = configDir.resolve("netherlink.json");
        JsonObject json = null;

        if (Files.exists(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                json = JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                // ⚠️ 解析失败**不回退到默认值静默运行**——那会让用户以为配置生效了。
                // 明确报错，并继续用默认值把服务端跑起来（不能因为配置文件坏掉就崩服）。
                NetherLinkNeoForge.LOGGER.error(
                        "配置文件解析失败（将使用默认值运行，请检查 {}）: {}",
                        file, e.getMessage());
            }
        } else {
            writeDefault(file);
        }

        return new NetherLinkConfig(
                str(json, "host", DEFAULT_HOST),
                num(json, "port", DEFAULT_PORT),
                str(json, "token", DEFAULT_TOKEN),
                str(json, "server-name", DEFAULT_SERVER_NAME),
                str(json, "wake-prefixes", DEFAULT_WAKE_PREFIXES)
        );
    }

    /** 写出默认配置。JSON 不支持注释，所以把说明放在 {@code _comment} 字段里。 */
    private static void writeDefault(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("_comment", "NetherLink MC 端配置（NeoForge 版）。键名与默认值"
                    + "与 Paper 端 netherlink-plugin 的 config.yml 一致。");
            o.addProperty("host", DEFAULT_HOST);
            o.addProperty("_comment_port", "AstrBot 插件监听的 WebSocket 端口，须与 ws_ports 对应");
            o.addProperty("port", DEFAULT_PORT);
            o.addProperty("_comment_token", "必须与 AstrBot 插件配置的 auth_token 一致");
            o.addProperty("token", DEFAULT_TOKEN);
            o.addProperty("_comment_server_name", "本服务器的标识（握手时上报）。"
                    + "显示名不在这里控制——QQ 群前缀、模板 {server}、AI 上下文里的"
                    + "服务器名统一由 AstrBot 的 server_display_names 决定");
            o.addProperty("server-name", DEFAULT_SERVER_NAME);
            o.addProperty("_comment_wake_prefixes", "游戏内机器人唤醒词（逗号分隔），"
                    + "与 AstrBot 侧 mc_wake_prefixes 保持一致");
            o.addProperty("wake-prefixes", DEFAULT_WAKE_PREFIXES);
            Files.writeString(file, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
            NetherLinkNeoForge.LOGGER.info("已生成默认配置: {}", file);
        } catch (IOException e) {
            NetherLinkNeoForge.LOGGER.warn("写默认配置失败（将用内存默认值运行）: {}", e.getMessage());
        }
    }

    private static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) {
            return def;
        }
        try {
            String v = o.get(key).getAsString();
            // 空串回退默认值——与 AstrBot 插件侧的「空串回退默认值」口径一致
            return v.isBlank() ? def : v;
        } catch (Exception e) {
            NetherLinkNeoForge.LOGGER.warn("配置项 {} 类型不对，已回退默认值", key);
            return def;
        }
    }

    private static int num(JsonObject o, String key, int def) {
        if (o == null || !o.has(key)) {
            return def;
        }
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            NetherLinkNeoForge.LOGGER.warn("配置项 {} 不是数字，已回退默认值 {}", key, def);
            return def;
        }
    }
}
