package dev.eyf.netherlink.neoforge;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 把带 {@code §} 染色码的整行文本解析成原版 {@link Component}。
 *
 * <p>⚠️ **为什么需要自己写**：Paper 端用的是 Adventure 的
 * {@code LegacyComponentSerializer}，那是 Paper/Bukkit 附带的库；
 * 原版 Minecraft 的 {@code Component} API **没有**内置的 legacy 反序列化
 * （NeoForge 也没有，它同样是原版环境）。所以这里手工解析——AstrBot 侧下发的
 * line 已经是渲染好的整行文本，语法只有「{@code §} + 一个字符」这一种形式，
 * 解析量很小。
 *
 * <p>支持的码：颜色 {@code 0-9a-f}、格式 {@code k-o}（加粗/斜体/下划线/删除线/乱码）、
 * {@code r} 重置。与 Minecraft 的 legacy 语义一致。
 *
 * <p>⚠️ 这个类**原样搬自 Fabric 端**——只用原版 API，与模组加载器无关。
 */
final class LegacyText {

    /** legacy 颜色码 -> 十六进制 RGB。与原版 {@code 0x000000 | ...} 取值一致。 */
    private static final Map<Character, String> COLORS = new HashMap<>();

    static {
        COLORS.put('0', "000000");
        COLORS.put('1', "0000AA");
        COLORS.put('2', "00AA00");
        COLORS.put('3', "00AAAA");
        COLORS.put('4', "AA0000");
        COLORS.put('5', "AA00AA");
        COLORS.put('6', "FFAA00");
        COLORS.put('7', "AAAAAA");
        COLORS.put('8', "555555");
        COLORS.put('9', "5555FF");
        COLORS.put('a', "55FF55");
        COLORS.put('b', "55FFFF");
        COLORS.put('c', "FF5555");
        COLORS.put('d', "FF55FF");
        COLORS.put('e', "FFFF55");
        COLORS.put('f', "FFFFFF");
    }

    private LegacyText() {
    }

    static Component parse(String line) {
        MutableComponent root = Component.empty();
        MutableComponent current = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // § 也可以是 &（AstrBot 侧对 QQ 消息做过 § -> & 的净化，
            // 避免伪造染色；但那一步只作用于**进站**消息，下行不会出现。
            // 这里只认 §，保持与原版一致。）
            if (c == '§' && i + 1 < line.length()) {
                char code = Character.toLowerCase(line.charAt(i + 1));
                i++;
                if (buf.length() > 0) {
                    current.append(Component.literal(buf.toString()).withStyle(style));
                    root.append(current);
                    current = Component.empty();
                    buf.setLength(0);
                }
                String color = COLORS.get(code);
                if (color != null) {
                    // 颜色码会**重置格式**（与原版 legacy 语义一致）
                    style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(color, 16)));
                } else if (code == 'r') {
                    style = Style.EMPTY;
                } else {
                    switch (code) {
                        case 'k' -> style = style.withObfuscated(true);
                        case 'l' -> style = style.withBold(true);
                        case 'm' -> style = style.withStrikethrough(true);
                        case 'n' -> style = style.withUnderlined(true);
                        case 'o' -> style = style.withItalic(true);
                        default -> {
                            // 未知码：原样保留这两个字符，别静默吞掉
                            buf.append('§').append(line.charAt(i));
                        }
                    }
                }
                continue;
            }
            buf.append(c);
        }
        if (buf.length() > 0) {
            current.append(Component.literal(buf.toString()).withStyle(style));
            root.append(current);
        }
        return root;
    }
}
