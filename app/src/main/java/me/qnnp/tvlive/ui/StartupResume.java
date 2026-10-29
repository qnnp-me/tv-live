package me.qnnp.tvlive.ui;

/**
 * onStart 恢复顺序（纯逻辑，便于单测）：
 *
 * <ol>
 *   <li>回前台先恢复引擎/Surface 并按当前台起播，但**不带 force**——同一台不重建解码器；</li>
 *   <li>静默重载完成后再判断：同一台已被重载保持/起播就跳过（不再 force 重播），
 *       列表里没这台就不强行恢复，播放编排里没有台时才补起一次。</li>
 * </ol>
 */
public final class StartupResume {

    public enum Action {
        /** 没有待恢复台。 */
        NONE,
        /** 列表里已没有这台（或被删/停用）：不恢复。 */
        DROP,
        /** 同一台已经起播：跳过，不再起第二个解码器。 */
        SKIP_SAME,
        /** 起播（force=false）。 */
        PLAY
    }

    private String pendingId;

    /** onStart：记下待恢复台；重载完成后由 {@link #settle} 判断要不要补播。 */
    public void defer(String channelId) {
        pendingId = channelId;
    }

    public String pendingId() {
        return pendingId;
    }

    public boolean hasPending() {
        return pendingId != null;
    }

    /** 重载自己已经处理了恢复：丢掉待恢复台，不再单独起播。 */
    public void discard() {
        pendingId = null;
    }

    /**
     * 重载完成时消费待恢复台。
     *
     * @param paused           当前是否又回到后台（不起播）
     * @param channelInPlaylist 待恢复台是否还在当前列表里
     * @param activeChannelId  播放编排当前台（null 表示没有）
     */
    public Action settle(boolean paused, boolean channelInPlaylist, String activeChannelId) {
        String id = pendingId;
        pendingId = null;
        if (id == null) {
            return Action.NONE;
        }
        if (paused || !channelInPlaylist) {
            return Action.DROP;
        }
        if (id.equals(activeChannelId)) {
            return Action.SKIP_SAME;
        }
        return Action.PLAY;
    }
}
