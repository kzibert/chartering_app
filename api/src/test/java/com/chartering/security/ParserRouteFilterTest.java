package com.chartering.security;

import com.chartering.config.ParserProperties;
import com.chartering.service.parser.ParserRoute;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ParserRouteFilterTest {

    private static final String KEY = "gsk_0123456789abcdef";

    @Test
    void shouldBindTheCallersCloudRoute_onAnIntakeRequest() throws Exception {
        ParserRoute seen = run(filter(true), "/api/v1/intake/sweep", "CLOUD", KEY);

        assertThat(seen.cloud()).isTrue();
        assertThat(seen.apiKey()).isEqualTo(KEY);
        assertThat(ParserRoute.current()).isEqualTo(ParserRoute.LOCAL);
    }

    @Test
    void shouldIgnoreTheHeaders_outsideIntake() throws Exception {
        assertThat(run(filter(true), "/api/v1/vessels", "CLOUD", KEY)).isEqualTo(ParserRoute.LOCAL);
    }

    @Test
    void shouldStayLocal_whenTheDeploymentDoesNotOfferTheCloud() throws Exception {
        assertThat(run(filter(false), "/api/v1/intake/status", "CLOUD", KEY)).isEqualTo(ParserRoute.LOCAL);
    }

    @Test
    void shouldStayLocal_withoutAUsableKey() {
        ParserRouteFilter filter = filter(true);

        assertThat(filter.routeOf("CLOUD", null)).isEqualTo(ParserRoute.LOCAL);
        assertThat(filter.routeOf("CLOUD", "   ")).isEqualTo(ParserRoute.LOCAL);
        assertThat(filter.routeOf("CLOUD", "has space")).isEqualTo(ParserRoute.LOCAL);
        assertThat(filter.routeOf("LOCAL", KEY)).isEqualTo(ParserRoute.LOCAL);
        assertThat(filter.routeOf(null, KEY)).isEqualTo(ParserRoute.LOCAL);
    }

    private static ParserRouteFilter filter(boolean cloudEnabled) {
        ParserProperties props = new ParserProperties();
        props.setCloudEnabled(cloudEnabled);
        return new ParserRouteFilter(props);
    }

    private static ParserRoute run(ParserRouteFilter filter, String uri, String provider, String key)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader(ParserRouteFilter.PROVIDER_HEADER, provider);
        request.addHeader(ParserRouteFilter.KEY_HEADER, key);
        AtomicReference<ParserRoute> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(jakarta.servlet.http.HttpServletRequest req,
                                           jakarta.servlet.http.HttpServletResponse res) {
                        seen.set(ParserRoute.current());
                    }
                }));
        return seen.get();
    }
}
