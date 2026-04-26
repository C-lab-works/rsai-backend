package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * Restricts incoming requests to those originating from Cloudflare's IP ranges.
 *
 * <p>In production (Azure Container Apps + Cloudflare proxy), the actual Cloudflare edge IP
 * arrives in the {@code X-Forwarded-For} header because the Azure Envoy sidecar rewrites
 * {@code getRemoteAddr()}. This filter locates the first non-private IP in the
 * X-Forwarded-For chain and verifies it is within a known Cloudflare CIDR.</p>
 *
 * <p>Set the environment variable {@code SKIP_CF_IP_CHECK=true} to bypass the check
 * in local development without Cloudflare in front.</p>
 *
 * <p>IP ranges are sourced from:
 * <ul>
 *   <li>https://www.cloudflare.com/ips-v4</li>
 *   <li>https://www.cloudflare.com/ips-v6</li>
 * </ul>
 * Update this list whenever Cloudflare publishes new ranges.</p>
 */
public class CloudflareIpFilter implements Handler {

    private static final Logger logger = new Logger(CloudflareIpFilter.class);

    /** Paths that are exempt from the IP check (must also be exempt from ApiKeyAuth). */
    private static final List<String> EXEMPT_PATHS = List.of("/health");

    // ── Cloudflare published CIDR ranges (last updated: 2026-04-26) ──────────

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

    public CloudflareIpFilter() {
        this.skipCheck = "true".equalsIgnoreCase(System.getenv("SKIP_CF_IP_CHECK"));
        if (skipCheck) {
            logger.warn("SKIP_CF_IP_CHECK=true — Cloudflare IP check is DISABLED (dev mode only)");
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

    // ── IP resolution ─────────────────────────────────────────────────────────

    /**
     * Extracts the Cloudflare edge IP from the request.
     *
     * <p>With Cloudflare in front:
     * <pre>Client → Cloudflare edge → Azure Container Apps (Envoy sidecar) → Java</pre>
     * The {@code X-Forwarded-For} header looks like:
     * <pre>X-Forwarded-For: &lt;real-client-ip&gt;, &lt;cloudflare-edge-ip&gt;</pre>
     * The last (rightmost) entry is the first-hop proxy that Azure's Envoy trusts,
     * which is the Cloudflare edge IP. We verify that entry is in the CF CIDR list.</p>
     */
    private String resolveCloudflareIp(Context ctx) {
        String xff = ctx.requestHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take the rightmost IP added by the nearest trusted proxy (Cloudflare)
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!ip.isEmpty() && !isPrivateOrLoopback(ip)) {
                    return ip;
                }
            }
            // Fallback: try CF-Connecting-IP (set by Cloudflare for the original client)
            // This header alone is not reliable for source verification but used as hint
        }

        // If no XFF header present, try Cf-Connecting-IP as last resort
        String cfConnectingIp = ctx.requestHeader("Cf-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            // Presence of this header without XFF means Cloudflare is not proxying
            // Return null to trigger 403
            return null;
        }

        return null;
    }

    // ── CIDR matching ─────────────────────────────────────────────────────────

    private boolean isCloudflareIp(String ipStr) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            return false;
        }
        for (CidrBlock block : blocks) {
            if (addr.getClass() != block.network().getClass()) continue; // IPv4 vs IPv6 mismatch
            if (matches(addr, block)) return true;
        }
        return false;
    }

    private boolean matches(InetAddress addr, CidrBlock block) {
        byte[] addrBytes    = addr.getAddress();
        byte[] networkBytes = block.network().getAddress();
        int    prefix       = block.prefix();

        int fullBytes  = prefix / 8;
        int remainder  = prefix % 8;

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
            return addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || ipStr.startsWith("10.")
                    || ipStr.startsWith("172.16.")
                    || ipStr.startsWith("172.17.")
                    || ipStr.startsWith("172.18.")
                    || ipStr.startsWith("172.19.")
                    || ipStr.startsWith("172.20.")
                    || ipStr.startsWith("172.21.")
                    || ipStr.startsWith("172.22.")
                    || ipStr.startsWith("172.23.")
                    || ipStr.startsWith("172.24.")
                    || ipStr.startsWith("172.25.")
                    || ipStr.startsWith("172.26.")
                    || ipStr.startsWith("172.27.")
                    || ipStr.startsWith("172.28.")
                    || ipStr.startsWith("172.29.")
                    || ipStr.startsWith("172.30.")
                    || ipStr.startsWith("172.31.")
                    || ipStr.startsWith("192.168.");
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // ── CIDR block builder ────────────────────────────────────────────────────

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
                logger.warn("Failed to parse CIDR '{}': {}", cidr, e.getMessage());
            }
        }
        return List.copyOf(result);
    }
}
