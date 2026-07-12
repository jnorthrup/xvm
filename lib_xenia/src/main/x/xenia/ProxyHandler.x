import web.HttpStatus;

import HttpServer.Handler;
import HttpServer.RequestInfo;

import ProxyConfig.Upstream;

/**
 * An HTTP request handler that acts as a reverse proxy, forwarding requests to an upstream server.
 * Invokes native Java logic to perform high-speed bidirectional data streaming and full cipher integration,
 * while allowing Xenia to maintain control over the request context, hooks, and logging.
 */
@Concurrent
service ProxyHandler
        implements Handler {

    Upstream upstream;

    construct(Upstream upstream) {
        this.upstream = upstream;
    }

    @Override
    void handle(RequestInfo request) {
        @Inject Console console;
        console.print($"[Proxy] Forwarding: {request.method} {request.uriString} -> {upstream.targetUri}");

        try {
            // Forward the heavy I/O proxy work to the native Java RTServer backend
            // which implements java.net.http.HttpClient for full cipher integration.
            request.proxyPass(upstream.targetUri);
        } catch (Exception e) {
            console.print($"[Proxy Error] {e.message}");
            request.respond(HttpStatus.BadGateway.code, [], [], []);
        }
    }

    @Override
    void close(Exception? e = Null) {}
}
