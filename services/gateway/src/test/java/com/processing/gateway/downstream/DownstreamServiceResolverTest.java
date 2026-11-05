package com.processing.gateway.downstream;

import com.processing.gateway.properties.GatewayRouteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamServiceResolverTest {

    @Test
    @DisplayName("Если настроен маршрут с Path=/api/internal/authorize/** и serviceName=\"authorization\", "
            + "а запрошен путь /api/internal/authorize/some/path, то resolve возвращает "
            + "Optional, содержащий \"authorization\"")
    void shouldResolveMatchingPath() {
        DownstreamServiceResolver resolver = new DownstreamServiceResolver(properties(
                route("auth", "authorization", List.of("Path=/api/internal/authorize/**"))
        ));

        Optional<String> service = resolver.resolve("/api/internal/authorize/some/path");

        assertThat(service).contains("authorization");
    }

    @Test
    @DisplayName("Если запрошенный путь /api/cards не соответствует "
            + "ни одному Path-предикату настроенных маршрутов, то resolve "
            + "возвращает пустой Optional")
    void shouldReturnEmptyForUnmatchedPath() {
        DownstreamServiceResolver resolver = new DownstreamServiceResolver(properties(
                route("auth", "authorization", List.of("Path=/api/internal/authorize/**"))
        ));

        assertThat(resolver.resolve("/api/cards")).isEmpty();
    }

    @Test
    @DisplayName("Если у маршрута с Path=/api/internal/authorize/** отсутствует serviceName "
            + "в metadata (пустая metadata), то такой маршрут игнорируется "
            + "и resolve возвращает пустой Optional")
    void shouldIgnoreRouteWithoutServiceName() {
        GatewayRouteProperties props = new GatewayRouteProperties();
        GatewayRouteProperties.RouteDefinition def = new GatewayRouteProperties.RouteDefinition();
        def.setId("auth");
        def.setMetadata(Map.of());
        def.setPredicates(List.of("Path=/api/internal/authorize/**"));
        props.setRoutes(List.of(def));

        DownstreamServiceResolver resolver = new DownstreamServiceResolver(props);

        assertThat(resolver.resolve("/api/internal/authorize/x")).isEmpty();
    }

    @Test
    @DisplayName("Если у маршрута с serviceName=\"authorization\" отсутствует Path-предикат "
            + "(задан только Method=POST), то такой маршрут игнорируется "
            + "и resolve возвращает пустой Optional")
    void shouldIgnoreRouteWithoutPathPredicate() {
        DownstreamServiceResolver resolver = new DownstreamServiceResolver(properties(
                route("auth", "authorization", List.of("Method=POST"))
        ));

        assertThat(resolver.resolve("/api/internal/authorize/x")).isEmpty();
    }

    @Test
    @DisplayName("Если настроены маршруты /api/cards/**→\"card-management\" "
            + "и /api/internal/authorize/**→\"authorization\", то resolve(\"/api/internal/authorize/x\") "
            + "возвращает \"authorization\", а resolve(\"/api/cards/4000001234567890\") — "
            + "\"card-management\"")
    void shouldResolveFirstMatchingRoute() {
        DownstreamServiceResolver resolver = new DownstreamServiceResolver(properties(
                route("cards", "card-management", List.of("Path=/api/cards/**")),
                route("auth", "authorization", List.of("Path=/api/internal/authorize/**"))
        ));

        assertThat(resolver.resolve("/api/internal/authorize/x")).contains("authorization");
        assertThat(resolver.resolve("/api/cards/4000001234567890")).contains("card-management");
    }

    private GatewayRouteProperties properties(GatewayRouteProperties.RouteDefinition... routes) {
        GatewayRouteProperties props = new GatewayRouteProperties();
        props.setRoutes(List.of(routes));
        return props;
    }

    private GatewayRouteProperties.RouteDefinition route(String id, String serviceName, List<String> predicates) {
        GatewayRouteProperties.RouteDefinition def = new GatewayRouteProperties.RouteDefinition();
        def.setId(id);
        def.setMetadata(Map.of("serviceName", serviceName));
        def.setPredicates(predicates);
        return def;
    }
}