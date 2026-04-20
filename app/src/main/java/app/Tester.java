package app;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.WsContext;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.WsMapping;

import java.util.Map;

@GateController
public class Tester {

    @GetMapping("/hello")
    public void hello(Context ctx) {
        String name = ctx.query("name");
        if (name == null || name.isBlank()) {
            ctx.status(400);
            ctx.result("name parameter is required");
            return;
        }
        ctx.result("Hello, " + name + "!");
    }

    @PostMapping("/echo")
    public void echo(Context ctx) {
        ctx.result("posted!");
    }

    @GetMapping("/json")
    public void json(Context ctx) {
        ctx.json(Map.of("message", "Hello from Gate!", "status", "ok"));
    }

    @GetMapping("/headers")
    public void headers(Context ctx) {
        ctx.header("X-Gate-Version", "0.1.0");
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.result("headers set!");
    }

    @WsMapping("/chat")
    public void chat(WsContext ctx, String message) {
        ctx.send("Echo: " + message);
    }

    @GetMapping("/error")
    public void error(Context ctx) {
        throw new RuntimeException("test error");
    }
}