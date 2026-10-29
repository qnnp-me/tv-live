package me.qnnp.tvlive.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 16：频道名归一化与中英别名（合并键）。
 */
public class ChannelAliasTest {

    // -------------------------------------------------------- 归一化

    @Test
    public void cctvSpellingsNormalizeToSameKey() {
        String expected = ChannelAlias.canonicalName("CCTV5");
        assertEquals("cctv5", expected);
        assertEquals(expected, ChannelAlias.canonicalName("CCTV-5"));
        assertEquals(expected, ChannelAlias.canonicalName("CCTV 5"));
        // 合并键也一致
        assertEquals(ChannelAlias.mergeKey("CCTV5"), ChannelAlias.mergeKey("CCTV-5"));
        assertEquals(ChannelAlias.mergeKey("CCTV5"), ChannelAlias.mergeKey("CCTV 5"));
    }

    @Test
    public void plusVariantNormalizesButStaysDistinctFromBase() {
        assertEquals(ChannelAlias.canonicalName("CCTV5+"), ChannelAlias.canonicalName("CCTV-5+"));
        assertEquals("cctv5+", ChannelAlias.canonicalName("CCTV-5+"));
        assertEquals(ChannelAlias.mergeKey("CCTV5+"), ChannelAlias.mergeKey("CCTV-5+"));
        assertNotEquals(ChannelAlias.mergeKey("CCTV5"), ChannelAlias.mergeKey("CCTV5+"));
        assertNotEquals(ChannelAlias.canonicalName("CCTV5"),
                ChannelAlias.canonicalName("CCTV5+"));
    }

    @Test
    public void stripsQualitySuffixesRepeatedly() {
        assertEquals(ChannelAlias.canonicalName("湖南卫视"),
                ChannelAlias.canonicalName("湖南卫视HD"));
        assertEquals(ChannelAlias.canonicalName("湖南卫视"),
                ChannelAlias.canonicalName("湖南卫视 HD 高清"));
        assertEquals(ChannelAlias.canonicalName("湖南卫视"),
                ChannelAlias.canonicalName("湖南卫视超清"));
        assertEquals(ChannelAlias.canonicalName("Hunan TV"),
                ChannelAlias.canonicalName("Hunan TV HD"));
        assertEquals("cctv1综合", ChannelAlias.canonicalName("CCTV-1 综合 1080p"));
    }

    @Test
    public void convertsFullWidthAndRemovesPunctuation() {
        assertEquals("cctv5", ChannelAlias.canonicalName("ＣＣＴＶ－５"));
        assertEquals("cctv5+", ChannelAlias.canonicalName("ＣＣＴＶ－５＋"));
        // & 先变 and，再去标点
        assertEquals("aande", ChannelAlias.canonicalName("A&E"));
        assertEquals(ChannelAlias.canonicalName("A&E"), ChannelAlias.canonicalName("A & E"));
    }

    // -------------------------------------------------------- 别名表

    @Test
    public void cctvAliasesShareStandardName() {
        assertEquals("CCTV1", ChannelAlias.canonical("央视一套"));
        assertEquals("CCTV1", ChannelAlias.canonical("CCTV1"));
        assertEquals("CCTV1", ChannelAlias.canonical("CCTV-1"));
        assertEquals("CCTV1", ChannelAlias.canonical("CCTV-1 综合"));
        assertEquals("CCTV1", ChannelAlias.canonical("CCTV1综合"));
        assertEquals(ChannelAlias.mergeKey("央视一套"), ChannelAlias.mergeKey("CCTV1"));
        assertEquals(ChannelAlias.mergeKey("央视一套"), ChannelAlias.mergeKey("CCTV-1"));
    }

    @Test
    public void cctv5PlusAnd4kKeepOwnIdentity() {
        assertEquals("CCTV5+", ChannelAlias.canonical("CCTV-5+"));
        assertNotEquals(ChannelAlias.mergeKey("CCTV5+"), ChannelAlias.mergeKey("CCTV5"));
        assertEquals("CCTV4K", ChannelAlias.canonical("CCTV-4K"));
        // CCTV4K 的键不能被 4k 后缀剥成 cctv，否则会和名叫 CCTV 的条目合并。
        assertNotEquals(ChannelAlias.mergeKey("CCTV4K"), ChannelAlias.mergeKey("CCTV"));
    }

    @Test
    public void satelliteAndEnglishAliasesShareStandardName() {
        assertEquals("湖南卫视", ChannelAlias.canonical("湖南卫视"));
        assertEquals("湖南卫视", ChannelAlias.canonical("Hunan TV"));
        assertEquals(ChannelAlias.mergeKey("湖南卫视"), ChannelAlias.mergeKey("Hunan TV"));
        assertEquals(ChannelAlias.mergeKey("湖南卫视"),
                ChannelAlias.mergeKey("Hunan TV HD"));

        assertEquals("东方卫视", ChannelAlias.canonical("Dragon TV"));
        assertEquals("浙江卫视", ChannelAlias.canonical("Zhejiang TV"));
        assertEquals("翡翠台", ChannelAlias.canonical("Jade"));
        assertEquals("凤凰卫视", ChannelAlias.canonical("凤凰卫视"));
        assertEquals("凤凰卫视", ChannelAlias.canonical("Phoenix TV"));
        assertEquals(ChannelAlias.mergeKey("凤凰卫视"),
                ChannelAlias.mergeKey("Phoenix TV"));
        assertEquals(ChannelAlias.mergeKey("凤凰资讯"),
                ChannelAlias.mergeKey("Phoenix InfoNews"));
    }

    @Test
    public void unknownChannelsFallBackToNormalization() {
        assertNull(ChannelAlias.canonical("安顺新闻综合频道"));
        assertEquals("安顺新闻综合频道", ChannelAlias.mergeKey("安顺新闻综合频道"));
        assertNotEquals(ChannelAlias.mergeKey("安顺新闻综合频道"),
                ChannelAlias.mergeKey("安庆新闻综合频道"));
    }

    // -------------------------------------------------------- 显示名判定

    @Test
    public void detectsLatinAndChineseNames() {
        assertTrue(ChannelAlias.isLatinName("Hunan TV HD"));
        assertTrue(ChannelAlias.isLatinName("CCTV-5"));
        assertFalse(ChannelAlias.isLatinName("湖南卫视"));
        assertFalse(ChannelAlias.isLatinName("CCTV-5 体育"));
        assertTrue(ChannelAlias.containsChinese("湖南卫视"));
        assertFalse(ChannelAlias.containsChinese("Hunan TV HD"));
    }
}
