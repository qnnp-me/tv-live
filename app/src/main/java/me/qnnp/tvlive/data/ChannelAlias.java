package me.qnnp.tvlive.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 频道名归一化 + 常见频道中英/别名表（纯 Java，无 Android 依赖）。
 *
 * <p>{@link #canonicalName} 只做「写法归一」，不查表：全角转半角、小写、
 * 去空白与标点（{@code &} 先变成 {@code and}）、循环剥离画质后缀。
 * 保留 {@code +}，所以 {@code CCTV5+} 不会被并到 {@code CCTV5}。</p>
 *
 * <p>{@link #canonical} 在这之上查内置别名表，命中返回标准名（如
 * {@code Hunan TV → 湖南卫视}）；表外返回 null，交给归一化串比较。</p>
 *
 * <p>{@link #mergeKey} 是合并键：命中别名时用标准名的键，否则用
 * {@link #canonicalName}。别名键不复用 {@link #canonicalName} 的 {@code 4k}
 * 剥离，否则 {@code CCTV4K} 会退化成 {@code cctv}，与名为 {@code CCTV} 的
 * 条目误合并。</p>
 *
 * <p>别名表只收常见频道，避免靠猜测扩展；同名英文词（Jade / Pearl /
 * Phoenix）存在极小概率误映射，见批次说明。</p>
 */
public final class ChannelAlias {

    /**
     * 画质后缀，长串在前（{@code 1080p} 先于 {@code 720p}，{@code uhd} 先于
     * {@code hd}，{@code 超高清} 先于 {@code 高清}、{@code 超清}），循环剥离到不再变化。
     */
    private static final String[] QUALITY_SUFFIXES = {
            "1080p", "720p", "超高清", "超清", "高清", "蓝光", "fhd", "uhd", "4k", "hd"
    };

    /** 别名表：归一化别名 → 标准名。 */
    private static final Map<String, String> ALIASES = buildAliases();

    private ChannelAlias() {
    }

    /** 标准名（中文优先）：命中别名表返回标准名，否则 null。 */
    public static String canonical(String raw) {
        String key = baseName(raw, false);
        return key.isEmpty() ? null : ALIASES.get(key);
    }

    /** 合并比较键：命中别名用标准名，否则用归一化名。 */
    public static String mergeKey(String raw) {
        String standard = canonical(raw);
        return standard == null ? canonicalName(raw) : baseName(standard, false);
    }

    /**
     * 写法归一化：全角转半角、trim、小写，去掉空白、{@code -}、{@code _}、
     * {@code ·}、{@code —}、{@code .}、{@code '}、{@code &}（{@code &} 先变成
     * {@code and}），再循环剥离画质后缀；保留 {@code +}。
     *
     * @return 归一化串；null/空输入返回空串
     */
    public static String canonicalName(String raw) {
        return baseName(raw, true);
    }

    /** 名称是否只含拉丁字母/数字/标点（可用于判断「纯英文名」）。 */
    public static boolean isLatinName(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isChinese(c)) {
                return false;
            }
            if (Character.isLetter(c)) {
                if (!isLatinLetter(c)) {
                    return false;
                }
                hasLetter = true;
            }
        }
        return hasLetter;
    }

    /** 名称是否含中文。 */
    public static boolean containsChinese(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (isChinese(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(String raw, boolean strip4k) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            } else if (c == '\u3000') {
                c = ' ';
            }
            char lower = Character.toLowerCase(c);
            if (lower == '&') {
                builder.append("and");
            } else if (!isIgnored(lower)) {
                builder.append(lower);
            }
        }
        return stripQualitySuffixes(builder.toString(), strip4k);
    }

    private static boolean isIgnored(char c) {
        return Character.isWhitespace(c)
                || c == '-' || c == '_' || c == '·' || c == '—'
                || c == '.' || c == '\'' || c == '’' || c == '&';
    }

    private static String stripQualitySuffixes(String text, boolean strip4k) {
        boolean changed = true;
        while (changed && text.length() > 1) {
            changed = false;
            for (String suffix : QUALITY_SUFFIXES) {
                if (!strip4k && "4k".equals(suffix)) {
                    continue;
                }
                // 要求剩余部分非空：单独一个「HD」不会被剥成空串。
                if (text.length() > suffix.length() && text.endsWith(suffix)) {
                    text = text.substring(0, text.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }

    private static boolean isChinese(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }

    private static boolean isLatinLetter(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            return true;
        }
        // Latin-1 Supplement / Latin Extended-A/B；其他文字不视为拉丁名。
        return c >= '\u00C0' && c <= '\u024F';
    }

    // ------------------------------------------------------------------ 别名表

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new HashMap<String, String>();

        // 央视：CCTV1..CCTV17 + CCTV5+ + CCTV4K
        addCctv(map, "1", "一", "综合");
        addCctv(map, "2", "二", "财经");
        addCctv(map, "3", "三", "综艺");
        addCctv(map, "4", "四", "中文国际");
        addCctv(map, "5", "五", "体育");
        addCctv(map, "6", "六", "电影");
        addCctv(map, "7", "七", "国防军事");
        addCctv(map, "8", "八", "电视剧");
        addCctv(map, "9", "九", "纪录");
        addCctv(map, "10", "十", "科教");
        addCctv(map, "11", "十一", "戏曲");
        addCctv(map, "12", "十二", "社会与法");
        addCctv(map, "13", "十三", "新闻");
        addCctv(map, "14", "十四", "少儿");
        addCctv(map, "15", "十五", "音乐");
        addCctv(map, "16", "十六", "奥林匹克");
        addCctv(map, "17", "十七", "农业农村");

        // 5+ 是独立频道，不能并到 CCTV5。
        add(map, "CCTV5+", "CCTV5+", "CCTV-5+", "CCTV5+体育赛事",
                "CCTV-5+ 体育赛事", "央视五套+", "央视5+");
        add(map, "CCTV4K", "CCTV4K", "CCTV-4K", "央视4K",
                "CCTV4K超高清", "CCTV-4K超高清");

        // 省级卫视（标准名中文）。
        add(map, "湖南卫视", "Hunan TV", "Hunan Satellite TV");
        add(map, "浙江卫视", "Zhejiang TV", "Zhejiang Satellite TV");
        add(map, "江苏卫视", "Jiangsu TV", "Jiangsu Satellite TV");
        add(map, "东方卫视", "Dragon TV", "上海东方卫视");
        add(map, "北京卫视", "Beijing TV", "Beijing Satellite TV");
        add(map, "安徽卫视", "Anhui TV");
        add(map, "山东卫视", "Shandong TV");
        add(map, "广东卫视", "Guangdong TV");
        add(map, "深圳卫视", "Shenzhen TV");
        add(map, "天津卫视", "Tianjin TV");
        add(map, "四川卫视", "Sichuan TV");
        add(map, "湖北卫视", "Hubei TV");
        add(map, "河南卫视", "Henan TV");
        add(map, "河北卫视", "Hebei TV");
        add(map, "江西卫视", "Jiangxi TV");
        add(map, "辽宁卫视", "Liaoning TV");
        add(map, "黑龙江卫视", "Heilongjiang TV");
        add(map, "吉林卫视", "Jilin TV");
        add(map, "陕西卫视", "Shaanxi TV");
        add(map, "山西卫视", "Shanxi TV");
        add(map, "云南卫视", "Yunnan TV");
        add(map, "贵州卫视", "Guizhou TV");
        add(map, "广西卫视", "Guangxi TV");
        add(map, "甘肃卫视", "Gansu TV");
        add(map, "宁夏卫视", "Ningxia TV");
        add(map, "青海卫视", "Qinghai TV");
        add(map, "新疆卫视", "Xinjiang TV");
        add(map, "西藏卫视", "Tibet TV", "Xizang TV");
        add(map, "内蒙古卫视", "Inner Mongolia TV", "Nei Mongol TV");
        add(map, "海南卫视", "Hainan TV");
        add(map, "东南卫视", "Southeast TV", "Fujian Southeast TV");
        add(map, "厦门卫视", "Xiamen TV");
        add(map, "海峡卫视", "Strait TV", "Haixia TV");

        // 港澳台常见频道。
        add(map, "翡翠台", "Jade", "TVB Jade", "无线翡翠台", "香港翡翠台");
        add(map, "明珠台", "Pearl", "TVB Pearl", "无线明珠台", "香港明珠台");
        add(map, "本港台", "ATV Home", "ATV本港台", "香港本港台", "亚视本港台");
        add(map, "无线新闻", "TVB News", "无线新闻台", "TVB News Channel");
        add(map, "凤凰卫视", "Phoenix TV", "Phoenix", "凤凰卫视中文台");
        add(map, "凤凰资讯", "Phoenix InfoNews", "Phoenix Info News", "凤凰资讯台");

        return map;
    }

    private static void addCctv(Map<String, String> map, String number, String chineseNumber,
            String category) {
        List<String> aliases = new ArrayList<String>();
        aliases.add("CCTV" + number + category);
        aliases.add("CCTV-" + number + " " + category);
        aliases.add("央视" + chineseNumber + "套");
        aliases.add("中央" + chineseNumber + "套");
        aliases.add("央视" + number + "套");
        add(map, "CCTV" + number, aliases.toArray(new String[aliases.size()]));
    }

    private static void add(Map<String, String> map, String standard, String... aliases) {
        register(map, standard, standard);
        for (String alias : aliases) {
            register(map, alias, standard);
        }
    }

    private static void register(Map<String, String> map, String alias, String standard) {
        String key = baseName(alias, false);
        if (!key.isEmpty() && !map.containsKey(key)) {
            map.put(key, standard);
        }
    }
}
