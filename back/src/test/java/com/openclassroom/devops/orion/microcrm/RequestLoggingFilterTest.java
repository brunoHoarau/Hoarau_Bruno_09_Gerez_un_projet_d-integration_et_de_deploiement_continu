package com.openclassroom.devops.orion.microcrm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @ParameterizedTest
    @CsvSource({ "200, INFO", "404, WARN", "500, ERROR" })
    void logsRequestWithLevelDependingOnStatus(int status, String expectedLevel) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/persons");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "la requête doit être transmise à la suite de la chaîne");
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.toLevel(expectedLevel), event.getLevel());
        String line = event.getFormattedMessage();
        assertTrue(line.contains("http_method=GET"), line);
        assertTrue(line.contains("url_path=/persons"), line);
        assertTrue(line.contains("http_status=" + status), line);
        assertTrue(line.contains("duration_ms="), line);
    }

    @Test
    void doesNotLogHealthcheckRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestLoggingFilter.HEALTHCHECK_HEADER, "true");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "la requête doit être transmise à la suite de la chaîne");
        assertTrue(appender.list.isEmpty());
    }
}
