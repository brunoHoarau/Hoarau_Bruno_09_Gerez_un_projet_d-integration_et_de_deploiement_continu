package com.openclassroom.devops.orion.microcrm;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Journalise chaque requête HTTP traitée par l'API : méthode, chemin, statut et durée.
 * Au format JSON (profil "json-logs"), ces informations deviennent des champs exploitables
 * dans Kibana (erreurs, volume, temps de réponse).
 * Le niveau dépend du statut : ERROR (5xx), WARN (4xx), INFO sinon.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** En-tête envoyé par le HEALTHCHECK Docker : ces requêtes ne sont pas journalisées. */
    static final String HEALTHCHECK_HEADER = "X-Healthcheck";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(HEALTHCHECK_HEADER) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            Object[] fields = {
                    kv("http_method", request.getMethod()),
                    kv("url_path", request.getRequestURI()),
                    kv("http_status", status),
                    kv("duration_ms", durationMs) };
            String message = "HTTP request {} {} {} {}";
            if (status >= 500) {
                log.error(message, fields);
            } else if (status >= 400) {
                log.warn(message, fields);
            } else {
                log.info(message, fields);
            }
        }
    }
}
