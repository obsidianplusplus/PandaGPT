package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.actions.ProposedCommand;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 Agent 回合的副作用记录：工具在主线程写入，回合结束后由服务层在主线程统一处理。
 *
 * <p>线程：只在服务器主线程读写；工作线程只通过调度器间接访问，因此不需要同步。
 */
public final class AgentTurn {
    /** 回合类型。 */
    public enum Kind {
        /** 玩家主动提问。 */
        CONVERSATION,
        /** 插件根据游戏信号主动发起。 */
        PROACTIVE
    }

    private final UUID player;
    private final Kind kind;
    private final String playerEvidence;
    private final List<ProposedCommand> proposals = new ArrayList<>();
    private final Set<String> toolsUsed = new LinkedHashSet<>();
    private boolean forgetConversation;
    private boolean silent;
    private boolean pendingAnswered;

    public AgentTurn(UUID player, Kind kind) {
        this(player, kind, "");
    }

    public AgentTurn(UUID player, Kind kind, String playerEvidence) {
        this.player = player;
        this.kind = kind;
        this.playerEvidence = playerEvidence;
    }

    public String playerEvidence() {
        return playerEvidence;
    }

    public UUID player() {
        return player;
    }

    public Kind kind() {
        return kind;
    }

    public List<ProposedCommand> proposals() {
        return proposals;
    }

    public Set<String> toolsUsed() {
        return toolsUsed;
    }

    public boolean forgetConversation() {
        return forgetConversation;
    }

    public void requestForget() {
        forgetConversation = true;
    }

    public boolean silent() {
        return silent;
    }

    public void stayQuiet() {
        silent = true;
    }

    /** 每个回合最多处理一次待确认动作，防止模型反复确认。 */
    public boolean claimPendingAnswer() {
        if (pendingAnswered) {
            return false;
        }
        pendingAnswered = true;
        return true;
    }
}
