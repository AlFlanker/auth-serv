package com.otr.portal.soap.wsdl;

import com.otr.portal.soap.wsdl.exception.WsdlLoadException;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.util.StringUtils.hasText;

/**
 * Helper для загрузки WSDL документов и их зависимостей (imports/includes).
 */
@Slf4j
class WsdlResourceLoader {
    public static final Pattern CHARSER_PATTERN = Pattern.compile("(?i)charset=\\s*([\\w\\-]+)");
    public static final Pattern ENCODING_PATTERN = Pattern.compile("(?i)encoding\\s*=\\s*\"([^\"]+)\"");
    private final Duration readTimeout = Duration.ofSeconds(10);
    private final URL baseUrl;
    private final WebClient webClient;

    /**
     * @param client  WebClient для загрузки wsdl
     * @param baseUrl базовый URL для резолва относительных путей
     */
    @Builder
    WsdlResourceLoader(WebClient client, URL baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = client;
    }

    /**
     * Загружает WSDL/XSD документ по указанному systemId.
     * <p>
     * Резолв URL выполняется лениво внутри реактивной цепочки, поэтому метод
     * не бросает проверяемых исключений и полностью неблокирующий.
     */
    Mono<String> loadDocument(String systemId) {
        return Mono.fromCallable(() -> resolveURL(systemId))
                .flatMap(this::loadResource);
    }

    /**
     * Загружает импортируемый документ с fallback-стратегией (поддержка ?xsd=n, .wsdl -> .xsd).
     * <p>
     * Реализация неблокирующая: варианты перебираются последовательно через
     * {@link Flux#concatMap}, первый успешно загруженный документ становится результатом.
     */
    Mono<String> loadImportWithFallback(String parentLocation, String importLocation) {
        return Mono.fromCallable(() -> resolveURLRelative(parentLocation, importLocation))
                .flatMap(this::loadResource)
                .onErrorResume(WsdlLoadException.class, originalException -> {
                    if (!isXsdResource(importLocation)) {
                        return Mono.error(originalException);
                    }
                    log.warn("Не удалось загрузить импорт {}, попробуем fallback-стратегии", importLocation);
                    return loadFromFallbackCandidates(parentLocation)
                            .switchIfEmpty(Mono.error(originalException));
                });
    }

    /**
     * Последовательно перебирает fallback-URL (.wsdl -> .xsd и {@code ?xsd=1..7})
     * и возвращает первый успешно загруженный документ.
     */
    private Mono<String> loadFromFallbackCandidates(String parentLocation) {
        return Mono.fromCallable(() -> buildFallbackCandidates(parentLocation))
                .flatMapMany(Flux::fromIterable)
                .concatMap(url -> loadResource(url)
                        .onErrorResume(WsdlLoadException.class, ignore -> Mono.empty()))
                .next();
    }

    private List<URL> buildFallbackCandidates(String parentLocation) throws IOException, URISyntaxException {
        URL parentUrl = resolveURL(parentLocation);
        List<URL> candidates = new ArrayList<>();

        var parentPath = parentUrl.getPath() == null
                ? ""
                : parentUrl.getPath();
        final var wsdlSuffix = ".wsdl";
        if (isWsdlResource(parentPath)) {
            int endIndex = parentPath.length() - wsdlSuffix.length();
            var xsdLocation = parentPath.substring(0, endIndex) + ".xsd"; // .wsdl на .xsd
            try {
                candidates.add(new URI(parentUrl.getProtocol(), null, parentUrl.getHost(),
                        parentUrl.getPort(), xsdLocation, null, null).toURL());
            } catch (URISyntaxException | MalformedURLException ignore) {
                // пропускаем некорректный кандидат
            }
        }

        // Пробуем варианты ?xsd=1..7. 7 как верний разумный предел
        var startQueryParam = parentUrl.toString().contains("?")
                ? "&"
                : "?";
        for (int i = 1; i <= 7; i++) {
            try {
                candidates.add(new URI(parentUrl + startQueryParam + "xsd=" + i).toURL());
            } catch (URISyntaxException | MalformedURLException ignore) {
                // пропускаем некорректный кандидат
            }
        }
        return candidates;
    }

    /**
     * Резолвит URL, поддерживая как абсолютные, так и относительные пути.
     */
    private URL resolveURL(String systemId) throws IOException, URISyntaxException {
        try {
            return new URI(systemId).toURL();
        } catch (URISyntaxException ignored) {
            // резолвим относительно базового
            log.trace("Резолв относительного URL {} относительно {}", systemId, baseUrl);
            URI resolvedUri = baseUrl.toURI()
                    .resolve(systemId); // резолвим относительную ссылку
            return resolvedUri.toURL();
        }
    }

    /**
     * Резолвит import относительно parentLocation.
     */
    private URL resolveURLRelative(String parentLocation, String importLocation) throws IOException, URISyntaxException {
        try {
            return new URI(importLocation).toURL();
        } catch (URISyntaxException ignored) {
            URL parent = resolveURL(parentLocation);
            return parent
                    .toURI()
                    .resolve(importLocation)
                    .toURL();
        }
    }

    private Mono<String> loadResource(URL url) {
        log.trace("Загрузка документа из {}", url);
        return webClient.get()
                .uri(url.toString())
                .header(ACCEPT, "application/xml, text/xml; q=0.9, */*; q=0.1")
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 200) {
                        var contentType = response.headers().contentType()
                                .map(Object::toString)
                                .orElse(null);
                        return response.bodyToMono(byte[].class)
                                .map(bytes -> {
                                    Charset charset =
                                            detectCharset(contentType, bytes);
                                    return new String(bytes, charset);
                                });
                    }
                    return response.releaseBody()
                            .then(Mono.error(new WsdlLoadException(
                                    "HTTP " + response.statusCode().value() + " при загрузке " + url)));
                })
                .timeout(readTimeout)
                .onErrorMap(ex ->
                                !(ex instanceof WsdlLoadException),
                        ex ->
                                new WsdlLoadException("Ошибка загрузки WSDL " + url, ex)
                );
    }

    private static Charset detectCharset(String contentType, byte[] body) {
        // Content-Type
        if (contentType != null) {
            var m = CHARSER_PATTERN.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignore) {
                    // пропускаем
                }
            }
        }
        // XML
        int length = Math.min(body.length, 256);
        String head = new String(body, 0, length, US_ASCII);
        var m2 = ENCODING_PATTERN
                .matcher(head);
        if (m2.find()) {
            try {
                return Charset.forName(m2.group(1));
            } catch (Exception ignore) {
                // пропускаем
            }
        }
        // Ничего не подошло. Мб ошибка, но лучше не придумал
        return UTF_8;
    }

    private static boolean isXsdResource(String location) {
        return hasText(location)
                && location.toLowerCase().endsWith(".xsd");
    }

    private static boolean isWsdlResource(String location) {
        return hasText(location)
                && location.toLowerCase().endsWith(".wsdl");
    }
}
