package dev.gate;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;

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

    @PostMapping("/schedules")
    public void create(Context ctx) {
        try {
            Schedule body = ctx.bodyAs(Schedule.class);
            if (body == null || body.getTitle() == null || body.getStartAt() == null || body.getEndAt() == null) {
                ctx.status(400).json(Map.of("error", "title, start_at and end_at are required"));
                return;
            }
            Schedule created = repo.create(body.getTitle(), body.getStartAt(), body.getEndAt());
            ctx.status(201).json(created);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/schedules/:id")
    public void update(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            Schedule body = ctx.bodyAs(Schedule.class);
            if (body == null || body.getTitle() == null || body.getStartAt() == null || body.getEndAt() == null) {
                ctx.status(400).json(Map.of("error", "title, start_at and end_at are required"));
                return;
            }
            repo.update(id, body.getTitle(), body.getStartAt(), body.getEndAt())
                .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "Not found")));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/schedules/:id")
    public void delete(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            if (repo.delete(id)) {
                ctx.status(204);
            } else {
                ctx.status(404).json(Map.of("error", "Not found"));
            }
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
