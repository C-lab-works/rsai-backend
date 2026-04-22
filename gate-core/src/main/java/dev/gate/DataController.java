package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;

import java.io.InputStream;
import java.util.Map;

@GateController
public class DataController {

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/events")
    public void events(Context ctx) {
        serveJson(ctx, "data/events.json");
    }

    @GetMapping("/food")
    public void food(Context ctx) {
        serveJson(ctx, "data/food.json");
    }

    @GetMapping("/map")
    public void map(Context ctx) {
        serveJson(ctx, "data/map.json");
    }

    private void serveJson(Context ctx, String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                ctx.status(404).json(Map.of("error", "Not found"));
                return;
            }
            Object data = mapper.readValue(is, Object.class);
            ctx.json(data);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
