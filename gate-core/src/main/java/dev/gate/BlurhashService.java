package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;
import io.trbl.blurhash.BlurHash;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class BlurhashService {

    private static final Logger logger = new Logger(BlurhashService.class);
    private static final HttpClient http = dev.gate.core.Http.CLIENT;

    /** blurhash が NULL の projects を処理し、生成できた件数を返す。 */
    public int generate() throws Exception {
        List<String[]> pending = fetchPending();
        int count = 0;
        for (String[] row : pending) {
            int id = Integer.parseInt(row[0]);
            String url = row[1];
            try {
                String hash = generateHash(url);
                if (hash != null) {
                    store(id, hash);
                    count++;
                }
            } catch (Exception e) {
                logger.warn("blurhash skip id={} url={}: {}", id, url, e.getMessage());
            }
        }
        logger.info("blurhash generated for {} projects", count);
        return count;
    }

    private List<String[]> fetchPending() throws Exception {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, image_url FROM projects WHERE blurhash IS NULL AND image_url IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new String[]{String.valueOf(rs.getInt("id")), rs.getString("image_url")});
            }
        }
        return result;
    }

    private String generateHash(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            logger.warn("HTTP {} for {}", res.statusCode(), url);
            return null;
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
        if (img == null) {
            logger.warn("ImageIO could not decode {}", url);
            return null;
        }
        return BlurHash.encode(img, 4, 3);
    }

    private void store(int id, String hash) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE projects SET blurhash = ? WHERE id = ?")) {
            ps.setString(1, hash);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }
}
