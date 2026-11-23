package com.panchayat.backend.rewards;

import com.panchayat.backend.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Stops one person farming rewards with many fake accounts from the same connection.
 *
 * A new account remembers the network it signed up from (users.signup_ip). Then:
 *  - the welcome red packet is offered to at most N accounts per network (the admin sets N; 0 = no limit),
 *  - a friend who joined from the inviter's own network, or from a network that already got its welcome
 *    gift, earns the inviter nothing,
 *  - gifts between two accounts from the same network can be switched off (CP partners are always allowed).
 *
 * A network is the IPv4 address, or the first 64 bits of an IPv6 address (one home or phone). Loopback, private
 * and carrier-grade-NAT addresses are ignored (shared by many people, or a proxy that hides the real address),
 * so nobody is blocked by a misconfigured proxy.
 */
@Component
public class NetworkGuard {

    public record Policy(int maxWelcomePerNetwork, boolean blockSameNetworkGifts) {
    }

    private final JdbcTemplate jdbc;

    public NetworkGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Policy policy() {
        Policy p = jdbc.query("select max_welcome_per_network, block_same_network_gifts from referral_settings where id = 1",
                rs -> rs.next() ? new Policy(rs.getInt(1), rs.getBoolean(2)) : null);
        return p != null ? p : new Policy(1, true);
    }

    /** The network of the request being served, or null when it can't be told apart (see the class note). */
    public String currentNetwork() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest req = attrs.getRequest();
        return networkOf(ClientIp.of(req));
    }

    public static String networkOf(String ip) {
        if (ip == null) return null;
        String s = ip.strip();
        if (s.isEmpty() || !s.matches("[0-9a-fA-F:.]+")) return null;
        try {
            InetAddress a = InetAddress.getByName(s);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) return null;
            byte[] b = a.getAddress();
            if (a instanceof Inet6Address) {
                StringBuilder out = new StringBuilder("v6:");
                for (int i = 0; i < 8; i++) out.append(String.format("%02x", b[i]));
                return out.toString();
            }
            // 100.64.0.0/10 is carrier-grade NAT: thousands of phones share one address
            if ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when both accounts signed up from the same (known) network. */
    public boolean sameNetwork(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) return false;
        Integer n = jdbc.queryForObject("select count(*) from users x join users y on y.signup_ip = x.signup_ip"
                + " where x.id = ? and y.id = ? and x.signup_ip is not null", Integer.class, a, b);
        return n != null && n > 0;
    }

    /** Gifts between these two are refused: same network, the admin switch is on, and they aren't CP partners. */
    public boolean giftBlocked(Long sender, Long receiver) {
        if (sender == null || receiver == null || sender.equals(receiver)) return false;
        if (!policy().blockSameNetworkGifts() || !sameNetwork(sender, receiver)) return false;
        Integer partner = jdbc.queryForObject("select count(*) from cp_members a join cp_members b on b.couple_id = a.couple_id where a.user_id = ? and b.user_id = ?",
                Integer.class, sender, receiver);
        return partner == null || partner == 0;
    }
}
