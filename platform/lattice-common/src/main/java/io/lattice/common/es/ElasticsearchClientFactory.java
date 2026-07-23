package io.lattice.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

/**
 * Builds the shared {@link ElasticsearchClient} (the official co.elastic.clients typed client)
 * that all repositories in this module read and write through. This is the single place a client
 * is constructed, so service code never hand-rolls a connection.
 *
 * <p>The client is configured with the Jackson JSON-P mapper (binding Java records/POJOs to
 * Elasticsearch documents through the one Jackson engine already on the classpath). Closing the
 * returned client closes its underlying transport and REST client.
 */
public final class ElasticsearchClientFactory {

    private ElasticsearchClientFactory() {
        // Static factory - not instantiable.
    }

    /**
     * Creates an {@link ElasticsearchClient} pointed at the given endpoint. The caller owns the
     * returned client's lifecycle and must {@link ElasticsearchClient#close() close} it on shutdown.
     *
     * @param url the Elasticsearch HTTP endpoint, including scheme (e.g. {@code http://localhost:9200}).
     * @return a configured, ready-to-use client.
     */
    public static ElasticsearchClient create(String url) {
        var restClient = RestClient.builder(HttpHost.create(url)).build();
        var transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
