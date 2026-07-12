import web.http.HostInfo;
import crypto.KeyStore;

/**
 * Configuration for a reverse proxy mapping domains to upstreams.
 */
const ProxyConfig(
    Map<HostInfo|String, Upstream> routes,
    KeyStore? defaultKeyStore = Null
) {
    /**
     * An upstream server definition.
     */
    const Upstream(
        String targetUri
    );
}
