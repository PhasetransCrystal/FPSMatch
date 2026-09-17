package com.ptcrys.fpsmatch.core.map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.core.FPSMCore;

import java.util.*;
import java.util.function.LongSupplier;

public class VoteObj {

    private final long endVoteTimer;
    private final LongSupplier clock;
    /** 时钟每秒对应的单位数：墙钟毫秒=1000，游戏 tick=20。 */
    private final long unitsPerSecond;
    private final String voteTitle;
    private final Component message;
    private final float requiredPercent;
    private final Map<UUID, Boolean> voteResults = new HashMap<>();
    private final Set<UUID> eligiblePlayers = new HashSet<>();
    private final Runnable onSuccess;
    private final Runnable onFailure;
    private final TimeoutPolicy timeoutPolicy;
    private final AbstentionPolicy abstentionPolicy;
    private VoteStatus status = VoteStatus.ONGOING;
    private boolean executed = false;

    // 投票状态枚举
    public enum VoteStatus {
        ONGOING,
        SUCCESS,
        FAILED
    }

    /**
     * 投票超时结算策略。
     * {@link #FAIL} —— 超时一律判失败（历史默认行为）。
     * {@link #PASS_IF_MAJORITY} —— 超时时按“已投票玩家的严格多数”结算，避免多数赞成却因超时被否决。
     *
     */
    public enum TimeoutPolicy {
        FAIL,
        PASS_IF_MAJORITY
    }

    /**
     * 弃权计票策略。
     * 
     * {@link #COUNT_AS_NO} —— 弃权视为反对：门槛分母为全部在线有资格者（历史默认行为）。
     * {@link #IGNORE} —— 弃权忽略：超时结算时分母只算已投票人数。
     * 
     */
    public enum AbstentionPolicy {
        COUNT_AS_NO,
        IGNORE
    }

    /**
     * @param voteTitle       投票标题
     * @param message         投票消息
     * @param duration        投票持续时间（秒）
     * @param requiredPercent 通过所需的玩家比例 (0.0 到 1.0)
     * @param onSuccess       投票成功时的回调
     * @param onFailure       投票失败时的回调
     * @param eligiblePlayers 有资格投票的玩家集合
     */
    public VoteObj(String voteTitle, Component message, int duration, float requiredPercent,
                   Runnable onSuccess, Runnable onFailure, Collection<UUID> eligiblePlayers) {
        this(voteTitle, message, duration, requiredPercent, onSuccess, onFailure, eligiblePlayers,
                TimeoutPolicy.FAIL, AbstentionPolicy.COUNT_AS_NO);
    }

    /**
     * 带超时/弃权策略的完整构造。旧的 7 参构造会以 {@link TimeoutPolicy#FAIL} +
     * {@link AbstentionPolicy#COUNT_AS_NO} 委托到此处，保证向后兼容。
     */
    public VoteObj(String voteTitle, Component message, int duration, float requiredPercent,
                   Runnable onSuccess, Runnable onFailure, Collection<UUID> eligiblePlayers,
                   TimeoutPolicy timeoutPolicy, AbstentionPolicy abstentionPolicy) {
        // 向后兼容：默认使用墙钟(毫秒)作为计时源
        this(voteTitle, message, duration, requiredPercent, onSuccess, onFailure, eligiblePlayers,
                timeoutPolicy, abstentionPolicy, System::currentTimeMillis, 1000L);
    }

    /**
     * 使用自定义时钟的完整构造。
     * <p>
     * 服务端可传入基于游戏时间(tick)的时钟与 {@code unitsPerSecond=20L}，
     * 使投票计时跟随游戏节奏而非墙钟，避免服务器暂停/卡顿时计时漂移。
     */
    public VoteObj(String voteTitle, Component message, int duration, float requiredPercent,
                   Runnable onSuccess, Runnable onFailure, Collection<UUID> eligiblePlayers,
                   TimeoutPolicy timeoutPolicy, AbstentionPolicy abstentionPolicy,
                   LongSupplier clock, long unitsPerSecond) {
        this.endVoteTimer = clock.getAsLong() + duration * unitsPerSecond;
        this.clock = clock;
        this.unitsPerSecond = unitsPerSecond;
        this.voteTitle = voteTitle;
        this.message = message;
        this.requiredPercent = Math.min(Math.max(requiredPercent, 0f), 1f); // 确保在0-1范围内
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.timeoutPolicy = timeoutPolicy == null ? TimeoutPolicy.FAIL : timeoutPolicy;
        this.abstentionPolicy = abstentionPolicy == null ? AbstentionPolicy.COUNT_AS_NO : abstentionPolicy;
        this.eligiblePlayers.addAll(eligiblePlayers);
    }

    /**
     * 处理玩家投票
     */
    public boolean processVote(ServerPlayer player, boolean agree) {
        if (status != VoteStatus.ONGOING) return false;

        UUID playerId = player.getUUID();

        // 检查玩家是否有资格投票
        if (!eligiblePlayers.contains(playerId)) {
            return false;
        }

        voteResults.put(playerId, agree);
        return true;
    }

    /**
     * 添加有资格投票的玩家
     */
    public void addEligiblePlayer(UUID playerId) {
        eligiblePlayers.add(playerId);
    }

    /**
     * 移除有资格投票的玩家
     */
    public void removeEligiblePlayer(UUID playerId) {
        eligiblePlayers.remove(playerId);
        voteResults.remove(playerId);
    }

    /**
     * 自动检查投票状态并执行相应操作
     * 
     * @return true 如果投票已结束，false 如果投票仍在进行中
     */
    public boolean tick() {
        if (status != VoteStatus.ONGOING || executed) {
            return true; // 投票已结束或已执行回调
        }

        int eligibleOnline = getEligiblePlayerCount();

        // 检查是否有资格投票的玩家
        if (eligibleOnline == 0) {
            status = VoteStatus.FAILED;
            executeCallback();
            return true;
        }

        // 仅统计在线玩家的票，避免掉线者导致分子/分母口径不一致（历史上可能出现比例 > 1）
        int agree = onlineAgreeCount();
        int voted = onlineVotedCount();

        // 提前通过：赞成票占“全部在线有资格者”的比例已达门槛，无论其余人如何投都已锁定通过
        if ((float) agree / eligibleOnline >= requiredPercent) {
            status = VoteStatus.SUCCESS;
            executeCallback();
            return true;
        }

        // 所有在线有资格者都投完但未达门槛 -> 失败
        if (voted >= eligibleOnline) {
            status = VoteStatus.FAILED;
            executeCallback();
            return true;
        }

        // 超时：按配置的超时/弃权策略结算
        if (clock.getAsLong() >= endVoteTimer) {
            status = resolveTimeout(agree, voted, eligibleOnline) ? VoteStatus.SUCCESS : VoteStatus.FAILED;
            executeCallback();
            return true;
        }

        return false;
    }

    /**
     * 超时结算：根据 {@link TimeoutPolicy} 与 {@link AbstentionPolicy} 判断是否通过。
     */
    private boolean resolveTimeout(int agree, int voted, int eligibleOnline) {
        if (timeoutPolicy == TimeoutPolicy.PASS_IF_MAJORITY) {
            int disagree = voted - agree;
            return agree > disagree; // 已投票中的严格多数
        }
        // FAIL 策略：仍尊重弃权口径——IGNORE 时分母只算已投票人数
        int denominator = abstentionPolicy == AbstentionPolicy.IGNORE ? voted : eligibleOnline;
        return denominator > 0 && (float) agree / denominator >= requiredPercent;
    }

    private boolean isOnline(UUID player) {
        return FPSMCore.getInstance().getPlayerByUUID(player).isPresent();
    }

    private int onlineAgreeCount() {
        int count = 0;
        for (Map.Entry<UUID, Boolean> entry : voteResults.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue()) && isOnline(entry.getKey())) {
                count++;
            }
        }
        return count;
    }

    private int onlineVotedCount() {
        int count = 0;
        for (UUID player : voteResults.keySet()) {
            if (isOnline(player)) {
                count++;
            }
        }
        return count;
    }

    /** 当前在线的同意票数（供投票 HUD 展示，口径与结算一致）。 */
    public int getOnlineAgreeCount() {
        return onlineAgreeCount();
    }

    /** 当前在线的反对票数。 */
    public int getOnlineDisagreeCount() {
        return onlineVotedCount() - onlineAgreeCount();
    }

    /** 当前在线的已投票人数。 */
    public int getOnlineVotedCount() {
        return onlineVotedCount();
    }

    /**
     * 执行相应的回调函数
     */
    private void executeCallback() {
        if (executed) return;

        executed = true;

        try {
            switch (status) {
                case SUCCESS:
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    break;
                case FAILED:
                    if (onFailure != null) {
                        onFailure.run();
                    }
                    break;
            }
        } catch (Exception e) {
            // 记录回调执行异常，避免影响主线程
            FPSMatch.LOGGER.error("Vote callback execution failed for '{}'", voteTitle, e);
        }
    }

    /**
     * 获取未投票的玩家ID
     */
    public Set<UUID> getNonVotingPlayers() {
        Set<UUID> nonVoting = new HashSet<>(eligiblePlayers);
        nonVoting.removeAll(voteResults.keySet());
        return nonVoting;
    }

    /**
     * 获取有资格投票的玩家数量
     */
    public int getEligiblePlayerCount() {
        int count = 0;
        for (UUID player : eligiblePlayers) {
            if (FPSMCore.getInstance().getPlayerByUUID(player).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取所有有资格投票的玩家
     */
    public Set<UUID> getEligiblePlayers() {
        return Collections.unmodifiableSet(eligiblePlayers);
    }

    // Getter 方法
    public Component getMessage() {
        return message;
    }

    public String getVoteTitle() {
        return voteTitle;
    }

    public float getRequiredPercent() {
        return requiredPercent;
    }

    public boolean isOvertime() {
        return "overtime".equals(voteTitle);
    }

    public VoteStatus getStatus() {
        return status;
    }

    public long getRemainingTime() {
        return Math.max(0, (endVoteTimer - clock.getAsLong()) / unitsPerSecond);
    }

    public int getAgreeCount() {
        return (int) voteResults.values().stream().filter(Boolean::booleanValue).count();
    }

    public int getDisagreeCount() {
        return (int) voteResults.values().stream().filter(v -> !v).count();
    }

    public int getVotedCount() {
        return voteResults.size();
    }

    public boolean hasExecuted() {
        return executed;
    }

    /**
     * 强制结束投票（用于特殊情况）
     */
    public void forceEnd(VoteStatus forcedStatus) {
        if (status == VoteStatus.ONGOING && !executed) {
            status = forcedStatus;
            executeCallback();
        }
    }
}
