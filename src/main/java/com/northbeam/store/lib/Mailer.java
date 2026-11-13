package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Notice;
import com.northbeam.store.lib.Models.User;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in mailer. Nothing leaves the process: notices go to an in-memory outbox.
 * sendSecurityNotice is the ONE place a security notice is sent and recorded.
 */
public final class Mailer {
    private Mailer() {}

    private static final List<Notice> outbox = new ArrayList<>();

    public record Sent(String status) {}

    public static Sent sendSecurityNotice(User user, String kind) {
        Notice n = new Notice();
        n.userId = user.id; n.to = user.email; n.kind = kind; n.channel = "email"; n.at = Clock.isoNow();
        Db.sync(() -> outbox.add(n));
        return new Sent("sent");
    }

    /**
     * The ONE place a verification code is "sent" (email or sms) and recorded. Nothing leaves the process.
     * shownCode is passed only in demo mode: it is then listed in the notice. Without it the notice never holds the code.
     */
    public static Sent sendVerificationCode(User user, String channel, String shownCode) {
        Notice n = new Notice();
        n.userId = user.id; n.to = channel.equals("sms") ? user.phone : user.email; n.kind = "verification_code"; n.channel = channel; n.at = Clock.isoNow();
        n.code = shownCode == null || shownCode.isEmpty() ? null : shownCode;
        Db.sync(() -> outbox.add(n));
        return new Sent("sent");
    }

    public static List<Notice> noticesFor(User user) {
        return Db.sync(() -> {
            List<Notice> mine = new ArrayList<>();
            for (Notice n : outbox) if (n.userId.equals(user.id)) mine.add(n);
            return mine;
        });
    }

    public static void reset() { Db.sync(outbox::clear); }
}
