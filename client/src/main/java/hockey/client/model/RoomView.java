package hockey.client.model;

import java.util.List;

/** 대기실에 그릴 방 상태. 서버가 방 상태를 보낼 때마다 새로 만든다. */
public record RoomView(String code, RoomSettings settings, List<Member> members, String myName, boolean started) {
    public RoomView(String code, RoomSettings settings, List<Member> members, String myName) {
        this(code, settings, members, myName, false);
    }
    public record Member(String name, boolean host, boolean ready) {}

    public Member me() {
        for (Member m : members) {
            if (m.name().equals(myName)) {
                return m;
            }
        }
        return null;
    }

    public boolean amHost() {
        Member me = me();
        return me != null && me.host();
    }

    /** 방장이 시작할 수 있는 상태: 두 명이 있고 방장이 아닌 사람이 모두 준비함. */
    public boolean canStart() {
        if (started || members.size() != RoomSummary.CAPACITY) {
            return false;
        }
        for (Member m : members) {
            if (!m.host() && !m.ready()) {
                return false;
            }
        }
        return true;
    }
}
