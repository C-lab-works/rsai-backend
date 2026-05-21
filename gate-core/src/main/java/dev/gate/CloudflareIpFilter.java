package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloudflareのいずれかのプロキシ経由のリクエストのみを許可するフィルタ。
 * Azure Container Apps環境ではEnvoyサイドカーがremoteAddrを書き換えるため、
 * X-Forwarded-Forの最右端IPを検証する。
 * ローカル開発時は環境変数 {@code SKIP_CF_IP_CHECK=true} でスキップ可能。
 * IPレンジは https://www.cloudflare.com/ips-v4 / ips-v6 を参照。
 */
public class CloudflareIpFilter implements Handler {

    private static final Logger logger = new Logger(CloudflareIpFilter.class);

    private static final List<String> EXEMPT_PATHS = List.of("/health");

    // Cloudflare CIDR一覧（最終更新: 2026-04-26）

    private static final String[] CF_CIDRS = {
        // IPv4 — https://www.cloudflare.com/ips-v4
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
        // IPv6 — https://www.cloudflare.com/ips-v6
        "2400:cb00::/32",
        "2606:4700::/32",
        "2803:f800::/32",
        "2405:b500::/32",
        "2405:8100::/32",
        "2a06:98c0::/29",
        "2c0f:f248::/32",
    };

    private record CidrBlock(InetAddress network, int prefix, int maxPrefix) {}

    private final List<CidrBlock> blocks;
    private final boolean skipCheck;

    // 文字列IP → Cloudflareマッチ結果のキャッシュ。InetAddress生成とCIDR線形走査を初回のみにする。
    // 7000reqのスパイクで同一edge IPが何千回も見えるためデカで効く。
    // キャッシュ上限とクリア閾値をもとにメモリ不足・多様なIP洪水を防ぐ。
    private static final int IP_CACHE_MAX = 50_000;
    private final ConcurrentHashMap<String, Boolean> ipMatchCache = new ConcurrentHashMap<>();

    public CloudflareIpFilter() {
        this.skipCheck = "true".equalsIgnoreCase(System.getenv("SKIP_CF_IP_CHECK"));
        if (skipCheck) {
            logger.warn("SKIP_CF_IP_CHECK=true — CloudflareIPチェック無効（開発環境専用）");
        }
        this.blocks = buildBlocks();
        logger.info("CloudflareIpFilter initialized with {} CIDR blocks", blocks.size());
    }

    @Override
    public void handle(Context ctx) {
        if (skipCheck) return;
        if (EXEMPT_PATHS.contains(ctx.path())) return;

        String candidateIp = resolveCloudflareIp(ctx);
        if (candidateIp == null || !isCloudflareIp(candidateIp)) {
            String xff = ctx.requestHeader("X-Forwarded-For");
            logger.warn("Request rejected: not from Cloudflare. candidate={} XFF={} path={}",
                    candidateIp, xff, ctx.path());
            ctx.status(403).json(Map.of("error", "Forbidden")).halt();
        }
    }

    // IP解決

    /**
     * X-Forwarded-Forの最右端IPを取得してCloudflare edgeのIPとして返す。
     * XFFヘッダーがない場合はnullを返す（Cloudflare未経由と判定）。
     */
    private String resolveCloudflareIp(Context ctx) {
        String xff = ctx.requestHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!ip.isEmpty() && !isPrivateOrLoopback(ip)) {
                    return ip;
                }
            }
        }
        return null;
    }

    // CIDRマッチング

    private boolean isCloudflareIp(String ipStr) {
        Boolean cached = ipMatchCache.get(ipStr);
        if (cached != null) return cached;

        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            putIpCache(ipStr, false);
            return false;
        }
        boolean matched = false;
        for (CidrBlock block : blocks) {
            if (addr.getClass() != block.network().getClass()) continue;
            if (matches(addr, block)) { matched = true; break; }
        }
        putIpCache(ipStr, matched);
        return matched;
    }

    private void putIpCache(String ipStr, boolean matched) {
        if (ipMatchCache.size() >= IP_CACHE_MAX) ipMatchCache.clear();
        ipMatchCache.put(ipStr, matched);
    }

    private boolean matches(InetAddress addr, CidrBlock block) {
        byte[] addrBytes    = addr.getAddress();
        byte[] networkBytes = block.network().getAddress();
        int    prefix       = block.prefix();

        int fullBytes = prefix / 8;
        int remainder = prefix % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (addrBytes[i] != networkBytes[i]) return false;
        }
        if (remainder > 0) {
            int mask = 0xFF & (0xFF << (8 - remainder));
            if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) return false;
        }
        return true;
    }

    private boolean isPrivateOrLoopback(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);
            // RFC1918プライベートアドレス（10.x, 172.16.x, 192.168.x）をカバー
            return addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // CIDRブロックビルダー

    private static List<CidrBlock> buildBlocks() {
        List<CidrBlock> result = new java.util.ArrayList<>();
        for (String cidr : CF_CIDRS) {
            try {
                int slash   = cidr.indexOf('/');
                String host = cidr.substring(0, slash);
                int    prefix = Integer.parseInt(cidr.substring(slash + 1));
                InetAddress network = InetAddress.getByName(host);
                result.add(new CidrBlock(network, prefix, network.getAddress().length * 8));
            } catch (Exception e) {
                logger.warn("CIDRパースエラー '{}': {}", cidr, e.getMessage());
            }
        }
        return List.copyOf(result);
    }
}
