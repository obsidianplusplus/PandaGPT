package cn.panda.pandagpt.service;

import cn.panda.pandagpt.ai.Message;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 按完整问答轮次保存的对话历史，超出字符预算时从最旧的轮次开始舍弃。
 *
 * <p>线程：由服务器主线程独占，只有不可变快照会离开主线程。
 */
public final class Conversation {
    private record Round(String question, String answer) {}

    private final ArrayDeque<Round> rounds = new ArrayDeque<>();
    private final int maxRounds;

    public Conversation(int maxRounds) {
        this.maxRounds = Math.max(0, maxRounds);
    }

    public void add(String question, String answer) {
        if (maxRounds == 0) return;
        rounds.addLast(new Round(question, answer));
        while (rounds.size() > maxRounds) rounds.removeFirst();
    }

    /** 清空全部历史，玩家要求“忘掉刚才的对话”时使用。 */
    public void clear() {
        rounds.clear();
    }

    public List<Message> snapshot(String question, int charBudget) {
        if (question.length() > charBudget) throw new IllegalArgumentException("Question exceeds context budget");
        ArrayDeque<Round> selected = new ArrayDeque<>();
        int used = question.length();
        var iterator = rounds.descendingIterator();
        while (iterator.hasNext()) {
            Round r = iterator.next();
            int size = r.question.length() + r.answer.length();
            if (used + size > charBudget) break;
            selected.addFirst(r);
            used += size;
        }
        List<Message> messages = new ArrayList<>();
        for (Round r : selected) {
            messages.add(new Message("user", r.question));
            messages.add(new Message("assistant", r.answer));
        }
        messages.add(new Message("user", question));
        return List.copyOf(messages);
    }
}
