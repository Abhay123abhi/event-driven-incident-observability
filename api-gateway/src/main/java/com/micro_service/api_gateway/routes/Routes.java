package com.micro_service.api_gateway.routes;

import com.micro_service.api_gateway.config.ServiceProperties;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;

import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;

@Configuration(proxyBeanMethods = false)
public class Routes {

    private final ServiceProperties services;

    public Routes(ServiceProperties services) {
        this.services = services;
    }

    @Bean
    public RouterFunction<ServerResponse> orderServiceRoute() {
        return GatewayRouterFunctions.route("order_service")
                .route(RequestPredicates.path("/api/order"), HandlerFunctions.http())
                .before(uri(services.order().url()))
                .filter(circuitBreaker("orderServiceCircuitBreaker", URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> orderServiceSwaggerRoute() {
        return GatewayRouterFunctions.route("order_service_swagger")
                .route(RequestPredicates.path("/aggregate/order-service/v3/api-docs"), HandlerFunctions.http())
                .before(uri(services.order().url()))
                .filter(circuitBreaker("orderServiceSwaggerCircuitBreaker", URI.create("forward:/fallbackRoute")))
                .filter(setPath("/api-docs"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> inventoryServiceRoute() {
        return GatewayRouterFunctions.route("inventory_service")
                .route(RequestPredicates.path("/api/inventory"), HandlerFunctions.http())
                .before(uri(services.inventory().url()))
                .filter(circuitBreaker("inventoryServiceCircuitBreaker", URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> inventoryServiceSwaggerRoute() {
        return GatewayRouterFunctions.route("inventory_service_swagger")
                .route(RequestPredicates.path("/aggregate/inventory-service/v3/api-docs"), HandlerFunctions.http())
                .before(uri(services.inventory().url()))
                .filter(circuitBreaker("inventoryServiceSwaggerCircuitBreaker", URI.create("forward:/fallbackRoute")))
                .filter(setPath("/api-docs"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> incidentServiceRoute() {
        return GatewayRouterFunctions.route("incident_service")
                .route(RequestPredicates.path("/api/incidents/**")
                        .and(RequestPredicates.method(HttpMethod.GET)), HandlerFunctions.http())
                .before(uri(services.incident().url()))
                .filter(circuitBreaker("incidentAnalyzerCircuitBreaker", URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> notificationSettingsRoute(
            @org.springframework.beans.factory.annotation.Value("${NOTIFICATION_SERVICE_URL:http://localhost:8083}") String url) {
        return GatewayRouterFunctions.route("notification_settings")
                .route(RequestPredicates.path("/api/notification-settings")
                        .and(RequestPredicates.method(HttpMethod.GET)
                                .or(RequestPredicates.method(HttpMethod.PUT))), HandlerFunctions.http())
                .before(uri(url))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> fallbackRoute() {
        return route("fallbackRoute")
                .GET("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Service Unavailable, please try again later"))
                .build();
    }
}
