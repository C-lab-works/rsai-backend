package dev.gate.core;

import org.eclipse.jetty.server.Server;

public class GateServer {

    private final Server server;

    GateServer(Server server) {
        this.server = server;
    }

    public void join() throws InterruptedException {
        server.join();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public boolean isRunning() {
        return server.isRunning();
    }
}
