package dev.gate;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;

import java.util.Map;

@GateController
public class ScheduleController {

    private final ScheduleRepository repo = new ScheduleRepository();

    @GetMapping("/schedules")
    public void list(Context ctx) {
        try {
            ctx.json(repo.findAll());
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/schedules/:id")
    public void get(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            repo.findById(id)
                .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "Not found")));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
