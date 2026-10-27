package io.github.topher6835.mediacompare.management;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class ManagementTestClient {
    private ManagementTestClient() { }

    static String capability(ManagementServer server) throws Exception {
        return capability(server.baseUri());
    }

    static String capability(java.net.URI base) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/api/management/bootstrap"))
                .header("X-Media-Compare-Bootstrap", "1").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("Bootstrap status " + response.statusCode());
        return response.body().split("\"capability\":\"")[1].split("\"")[0];
    }

    static HttpRequest.Builder authorized(ManagementServer server, String path) throws Exception {
        return HttpRequest.newBuilder(server.baseUri().resolve(path))
                .header(LocalApiSecurity.ACCESS_HEADER, capability(server));
    }
}
