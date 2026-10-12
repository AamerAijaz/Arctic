package dev.arctic.polarion;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Factory for the HTTP stack Polarion calls use. The RestClient is not a Spring bean. */
public final class PolarionClients {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private PolarionClients() {
    }

    public static RestClient restClient() {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            http.proxy(proxy);
        }
        var factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder()
                .requestFactory(factory)
                // PolarionClient parses JSON itself; Jackson would treat a String as a JSON value.
                .configureMessageConverters(client -> client.registerDefaults()
                        .configureMessageConvertersList(list ->
                                list.removeIf(JacksonJsonHttpMessageConverter.class::isInstance)))
                .build();
    }
}
