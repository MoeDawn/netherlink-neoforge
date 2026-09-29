package dev.eyf.netherlink.neoforge;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/**
 * 指令执行结果的收集器：同时充当「输出去向」与「结果回调」。
 *
 * <p>用法：
 * <pre>{@code
 * var capture = new CommandCapture();
 * var source = server.createCommandSourceStack()
 *         .withSource(capture)                                  // ① 收指令输出
 *         .withCallback(capture)                                // ② 收「指令有没有真的跑起来」
 *         .withPermission(PermissionSet.ALL_PERMISSIONS);       // 权限等同控制台
 * server.getCommands().performPrefixedCommand(source, cmd);
 * }</pre>
 *
 * <p>两个信息来自原版两个不同的回调，缺一不可：
 * <ul>
 *   <li>{@link CommandSource#sendSystemMessage} 只给**文本**，不区分成功与失败；</li>
 *   <li>{@link CommandResultCallback#onResult} 只给**信号**，不给文本。</li>
 * </ul>
 *
 * <p><b>⚠️ {@link #ok()} 的判据是「有没有收到过 onResult」，不是「onResult 报的是成功还是失败」。</b>
 * 这一点是**实机测出来**的，照直觉写会错：
 *
 * <pre>
 * 指令                     原版 onResult        本类的 ok()    Paper 端 dispatchCommand
 * give @a apple 1（没人在线）  onResult(false)     true          true
 * nonexistentcommand123     没调用               false         false
 * </pre>
 *
 * 原因：原版的 {@code wasSuccess} 用的是**它自己的**语义（「@a 一个玩家都没匹配到」
 * 在原版眼里算失败），而 AstrBot 侧关心的是**「这条指令到底有没有被执行」**——
 * 与 Bukkit 的 {@code dispatchCommand} 语义一致。所以取「回调有没有被调用」这个
 * 更强的信号：**onResult 被调用 ⟺ 指令通过了 Brigadier 解析并进入了执行**；
 * 解析失败时原版直接返回，根本不会通知 callback。
 *
 * <p>⚠️ 与 Paper 端还有一处差别：{@code performPrefixedCommand} 返回 <b>void</b>，
 * 而 Bukkit 的 {@code dispatchCommand} 返回 boolean。所以成败只能靠这里收。
 *
 * <p>注意「执行了但没有输出」是**正常成功**——原版不少指令不给执行者反馈
 * （{@code tp} / {@code kill} / {@code say} 都是），不能把 output 为空当成失败。
 *
 * <p>⚠️ 这个类**原样搬自 Fabric 端**：它只用原版 API（{@code net.minecraft.commands.*}），
 * 而 NeoForge 也是原版环境——不像 Paper 有 Bukkit 封装。所以那条
 * 「费了两轮才对齐的 ok 判据」原样继续成立。
 */
final class CommandCapture implements CommandSource, CommandResultCallback {

    private final StringBuilder out = new StringBuilder();
    private boolean callbackInvoked = false;

    /** 累积的输出文本（各行以换行分隔）。 */
    String text() {
        return out.toString();
    }

    /**
     * 这条指令是否真的被执行了。判据见类注释：**收到过 onResult 就算执行了**。
     */
    boolean ok() {
        return callbackInvoked;
    }

    // ------------------------------------------------------------------
    // CommandSource：收指令输出
    // ------------------------------------------------------------------
    @Override
    public void sendSystemMessage(Component message) {
        String s = message.getString();
        if (!s.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(s);
        }
    }

    /**
     * 是否接受成功反馈。
     *
     * <p>返回 true，否则 {@code CommandSourceStack.sendSuccess} 不会把成功消息
     * 转给本 Source——{@code time set day} 这类指令的输出就一条都收不到。
     */
    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        // 冒充控制台执行，不需要对管理员额外播报——输出已回传 AstrBot，由它决定怎么呈现。
        return false;
    }

    // ------------------------------------------------------------------
    // CommandResultCallback：收「指令跑起来了」这个信号
    // ------------------------------------------------------------------
    @Override
    public void onResult(boolean wasSuccess, int result) {
        // ⚠️ 这里**刻意忽略 wasSuccess**，只记「回调被调用过」。
        // 理由见类注释：原版的成败语义与 AstrBot 需要的「有没有执行」不一致。
        callbackInvoked = true;
    }
}
