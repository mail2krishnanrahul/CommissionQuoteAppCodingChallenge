package com.lendingplatform.quoteapp.vendor;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The HTTP client for the vendor: the JDK's own client, with both timeouts set so nothing can hang. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VendorClientProperties.class)
public class VendorClientConfig {

    @Bean
    public RestClient vendorRestClient(VendorClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Plain HTTP/1.1: no cleartext HTTP/2 upgrade attempt on every call.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
