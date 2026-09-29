# NetherLink MC 端 · NeoForge 版

Minecraft **NeoForge** 服务端模组，与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连接，实现服务器与 QQ 群的双向消息互通。

**必须先装好 AstrBot 侧插件**，本模组才能工作（它是客户端，主动连入 AstrBot）。

> 📌 另有 [Paper / Purpur / Folia 版](https://github.com/MoeDawn/netherlink-plugin) 与
> [Fabric 版](https://github.com/MoeDawn/netherlink-fabric)。
> 三个版本**协议完全相同**，接同一个 AstrBot 插件，服务端不用改配置。

---

## 功能

| 方向 | 说明 |
|---|---|
| 游戏 → QQ | 聊天 / 进服 / 退服 / 死亡推送到群；唤醒词开头的话作为对话交给 AI |
| 成就上报 | 玩家获得成就时通知 AI（已过滤配方解锁与根成就） |
| QQ → 游戏 | 群消息渲染 § 染色码后广播到公屏 |
| 指令执行 | 以控制台身份执行 AI 下发的指令，并把服务器**真实输出**回传给 AI |

---

## 环境要求

- Minecraft **26.3**
- **[NeoForge](https://neoforged.net/)** `26.3.0.23-beta`
- **Java 25**
- 已装好并运行 [AstrBot 侧插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)

> ⚠️ 只支持 **Minecraft 26.3**，其他 MC 版本暂未适配。
> ⚠️ NeoForge 26.3 目前是 **beta** 版。

---

## 安装

1. **下载 Release**
   从 [Releases](https://github.com/MoeDawn/netherlink-neoforge/releases) 下载 `netherlink-neoforge-0.0.1.jar`

2. **放入服务端**
   把 jar 放进服务器的 `mods/` 目录，重启服务器

3. **填配置**
   首次启动会生成 `config/netherlink.json`：

   ```json
   {
     "host": "AstrBot机器的IP",
     "port": 8765,
     "token": "与 AstrBot 侧 auth_token 完全一致",
     "server-name": "survival",
     "wake-prefixes": "ai,助手"
   }
   ```

4. **重启服务器**使配置生效

连接成功后，AstrBot 日志会显示握手成功，游戏内事件即开始推送到群。

---

## 配置项

| 配置项 | 说明 |
|---|---|
| `host` / `port` | AstrBot 侧监听的 WebSocket 地址与端口 |
| `token` | 握手鉴权密钥，两侧必须一致 |
| `server-name` | 本服务器的**身份**，握手时上报。AstrBot 侧的 `ws_ports` / `server_display_names` 用它区分与命名各台服务器（不影响显示名） |
| `wake-prefixes` | 游戏内唤醒词，**须与 AstrBot 侧 `mc_wake_prefixes` 一致**否则唤不醒 AI |

> `server-name` 是身份，**显示名不在这里控制**——QQ 群前缀、`{server}` 占位符、
> AI 上下文里的服务器名统一由 AstrBot 侧的 `server_display_names` 决定（没配则显示 `MC`）。

**键名与默认值与 Paper 端的 `config.yml` 逐字对齐**——换端时配置可以直接照搬。

> `wake-prefixes` 支持**中文全角分隔符**：全角逗号「，」、顿号「、」、分号「；」
> 都会被归一化成半角逗号再切分——中文输入法下很容易打出这些。
>
> 配置写错时一律**回退默认值并记 warning**，不会因为配置问题崩掉服务端。

---

## 可靠性

- 断线**自动重连**，指数退避（3 秒起，最多 60 秒）
- **15 秒心跳**保活
- 重连与心跳都在异步线程执行，**不阻塞服务器主线程**

---

## 状态

| 功能 | 状态 |
|---|---|
| WebSocket 连接 / 握手 / 重连 / 心跳 | ✅ 已验证 |
| 指令执行 + 输出捕获 | ✅ 已验证（5 条指令与 Paper 端逐一对照一致） |
| 进服 / 退服 / 死亡上报 | ✅ 已验证 |
| 成就上报 | ✅ 已验证（配方解锁与根成就均正确过滤） |
| 配置生成与生效 | ✅ 已验证 |
| 聊天（含唤醒词分流） | ⚠️ 已实现，仅编译验证 |

> ⚠️ **聊天**目前只做了编译验证。它是唯一没法用控制台指令模拟的事件，只能靠真人
> 敲键盘，而自动化脚本在本机拿不到窗口焦点。上手前建议先在测试服上敲一句
> `ai 你好`，确认 AI 有回应。

---

## 从源码构建

需要 **JDK 25**，用工程自带的 Gradle wrapper。

```bash
cd netherlink-neoforge
./build.cmd          # Windows
```

产物：`build/libs/netherlink-neoforge-0.0.1.jar`

> ⚠️ 若所在网络**无法直连 `maven.neoforged.net`**：`build.cmd` 已内置
> `tools/neoforge-mirror.init.gradle`，会把依赖重定向到官方分发渠道
> `neoforged.forgecdn.net`。能直连的机器可以去掉那个 `--init-script` 参数。

---

## 许可

[MIT License](LICENSE)
