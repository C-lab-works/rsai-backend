package app;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import java.util.Map;
import java.util.HashMap;

@GateController
public class Tester {

    @GetMapping("/hello")
    public void hello(Context ctx) {
        String name = ctx.query("name");
        ctx.result("Hello, " + name + "!");
    }

    @PostMapping("/echo")
    public void echo(Context ctx) {
        ctx.result("posted!");
    }

    @GetMapping("/json")
    public void json(Context ctx) {
        Map<String, String> data = new HashMap<>();
        data.put("message", "Hello from Gate!");
        data.put("status", "ok");
        ctx.json(data);
    }

    @GetMapping("/headers")
    public void headers(Context ctx) {
        ctx.header("X-Gate-Version", "0.1.0");
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.result("headers set!");
    }
}