package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;

class AuditLog {
    private static final Logger logger = new Logger(AuditLog.class);

    private AuditLog() {}

    static void write(String user, String action, String target, String detail, String result, String errorMsg) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO operation_logs (user,action,target,detail,result,error_message,timestamp) " +
                    "VALUES (?,?,?,?,?,?,NOW())")) {
                ps.setString(1, user != null ? user : "unknown");
                ps.setString(2, action);
                ps.setString(3, target != null ? target.substring(0, Math.min(255, target.length())) : "");
                ps.setString(4, detail);
                ps.setString(5, result);
                ps.setString(6, errorMsg);
                ps.executeUpdate();
            } catch (Exception e) {
                logger.warn("AuditLog.write failed: {}", e.getMessage());
            }
        });
    }
}
