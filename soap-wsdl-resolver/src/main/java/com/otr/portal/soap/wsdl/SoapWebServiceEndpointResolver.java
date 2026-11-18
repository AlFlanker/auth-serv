package com.otr.portal.soap.wsdl;

import com.otr.portal.soap.wsdl.exception.WsdlEndpointResolutionException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.unmodifiableMap;
import static java.util.function.Predicate.not;
import static org.springframework.util.StringUtils.hasText;

/**
 * Определяет URL, SoapAction
 * и целевой namespace SOAP web-сервиса по его WSDL.
 * <p>
 * Экземпляр резолвера потокобезопасен для параллельных вызовов {@link #resolveEndpointMetadata}.
 * В проекте пока не используется.
 *
 * @author alexraspopin
 * @since 14.07.2026
 */
@Slf4j
public class SoapWebServiceEndpointResolver implements WebServiceEndpointResolver {
    private static final String WSDL_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP11_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/soap12/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Общий таймаут на загрузку всех документов и парсинг: защищает от сервера,
     * который принял соединение, но не отвечает.
     */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);
    // Глубна
    private static final int MAX_IMPORT_DEPTH = 3;
    // Кол-во доков
    private static final int MAX_WSDL_DOCUMENTS = 8;
    // лимит размера wsdl
    private static final int MAX_WSDL_SIZE_CHARS = 8 * 1024 * 1024;

    private final WebClient webClient;

    public SoapWebServiceEndpointResolver(WebClient.Builder baseBuilder) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // У нас есть старые сервера, которые не умеют в HTTP/2
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        this.webClient = baseBuilder
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .build();
    }

    /**
     * Реактивно определяет метаданные endpoint по WSDL.
     */
    @Override
    public Mono<ResolvedSoapEndpoint> resolveEndpointMetadata(EndpointContext request) {
        return loadWsdlModel(request)
                .map(model ->
                        resolveEndpoint(model, request))
                .timeout(RESOLUTION_TIMEOUT)
                .onErrorMap(TimeoutException.class, e -> new WsdlEndpointResolutionException(
                        "Превышен общий таймаут " + RESOLUTION_TIMEOUT + " резолва WSDL " + request.getUrl(), e))
                .onErrorMap(e -> !(e instanceof WsdlEndpointResolutionException),
                        e -> new WsdlEndpointResolutionException(
                                "Ошибка загрузки/парсинга WSDL " + request.getUrl(), e));
    }

    // Загрузка дерева документов
    private Mono<WsdlModel> loadWsdlModel(EndpointContext request) {
        return Mono.fromCallable(() ->
                        createLoadSession(request.getUrl()))
                .flatMapMany(session ->
                        loadDocumentTree(session, session.rootLocation(), 0))
                .collectList()
                .map(this::mergeDocuments);
    }

    private LoadSession createLoadSession(String wsdlUrl) throws MalformedURLException {
        URI rootLocation = requireHttpUri(URI.create(wsdlUrl)).normalize();
        var loader = WsdlResourceLoader.builder()
                .client(webClient)
                .baseUrl(rootLocation.toURL())
                .build();
        Set<URI> visited = ConcurrentHashMap.newKeySet();
        visited.add(rootLocation);
        return new LoadSession(loader, rootLocation, visited);
    }

    private Flux<WsdlDocument> loadDocumentTree(LoadSession session, URI location, int depth) {
        return session.loader().loadDocument(location.toString())
                // Парсинг - неблокирующая CPU-работа, поэтому уводим её с event-loop
                .publishOn(Schedulers.parallel())
                .map(xml ->
                        parseWsdlDocument(xml, location))
                .flatMapMany(document ->
                        Flux.concat(
                                Mono.just(document),
                                followImports(session, document, location, depth)));
    }

    private Flux<WsdlDocument> followImports(
            LoadSession session,
            WsdlDocument document,
            URI parentLocation, int depth
    ) {
        if (document.imports().isEmpty()) {
            return Flux.empty();
        }
        if (depth >= MAX_IMPORT_DEPTH) {
            return Flux.error(new WsdlEndpointResolutionException(
                    "Превышена максимальная глубина wsdl:import (" + MAX_IMPORT_DEPTH + ") в " + parentLocation));
        }
        return Flux.fromIterable(document.imports())
                .mapNotNull(importLocation ->
                        registerImport(session, parentLocation, importLocation))
                .concatMap(importUri ->
                        loadDocumentTree(session, importUri, depth + 1));
    }

    /**
     * @return абсолютный URI импорта или {@code null}, если документ уже загружен (защита от циклов и дублей)
     */
    @Nullable
    private URI registerImport(LoadSession session, URI parentLocation, String importLocation) {
        URI resolved = requireHttpUri(parentLocation.resolve(importLocation)).normalize();
        if (!session.visited().add(resolved)) {
            return null;
        }
        if (session.visited().size() > MAX_WSDL_DOCUMENTS) {
            throw new WsdlEndpointResolutionException(
                    "Превышен лимит количества WSDL-документов (" + MAX_WSDL_DOCUMENTS + ") при загрузке импортов "
                            + parentLocation);
        }
        return resolved;
    }

    private WsdlDocument parseWsdlDocument(String xml, URI location) {
        if (xml.length() > MAX_WSDL_SIZE_CHARS) {
            throw new WsdlEndpointResolutionException("WSDL " + location + " слишком большой: "
                    + xml.length() + " символов при лимите " + MAX_WSDL_SIZE_CHARS);
        }
        try {
            var stringReader = new StringReader(xml);
            XMLStreamReader reader = createSecureXmlInputFactory()
                    .createXMLStreamReader(stringReader);
            try {
                return new WsdlDocumentParser(reader).parse();
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new WsdlEndpointResolutionException("Ошибка парсинга WSDL " + location, e);
        }
    }

    private WsdlModel mergeDocuments(List<WsdlDocument> documents) {
        WsdlDocument root = documents.getFirst();
        List<WsdlService> services =
                new ArrayList<>();
        Map<WsdlQName, Map<String, String>> bindings =
                new LinkedHashMap<>();
        for (WsdlDocument document : documents) {
            services.addAll(document.services());
            document.bindings()
                    .forEach(bindings::putIfAbsent);
        }

        return new WsdlModel(root.targetNamespace(),
                List.copyOf(services),
                unmodifiableMap(bindings)
        );
    }


    private ResolvedSoapEndpoint resolveEndpoint(WsdlModel model, EndpointContext request) {
        if (!hasText(model.targetNamespace())) {
            // Контракт ResolvedSoapEndpoint допускает null, но без targetNamespace
            log.warn("В WSDL {} у корневого wsdl:definitions отсутствует targetNamespace", request.getUrl());
        }
        WsdlService service = selectService(model, request);
        WsdlPort port = selectPort(service, request);
        String soapAction = resolveSoapAction(model, port, request);

        log.trace("Резолв WSDL {} завершён: endpoint={}, soapAction={}, targetNamespace={}",
                request.getUrl(), port.address(), soapAction, model.targetNamespace());

        return ResolvedSoapEndpoint.builder()
                .endpointUrl(port.address())
                .soapAction(soapAction)
                .targetNamespace(model.targetNamespace())
                .build();
    }

    private WsdlService selectService(WsdlModel model, EndpointContext request) {
        var requestedName = request.getServiceName();
        if (hasText(requestedName)) {
            for (WsdlService service : model.services()) {
                if (requestedName.equals(service.name())) {
                    return service;
                }
            }
        }

        return selectFallbackService(model, request, requestedName);
    }

    private WsdlService selectFallbackService(WsdlModel model, EndpointContext request, @Nullable String requestedName) {
        var callable = model.services().stream()
                .filter(not(service ->
                        service.ports().isEmpty()))
                .toList();
        if (callable.size() == 1) {
            WsdlService fallback = callable.getFirst();
            if (hasText(requestedName)) {
                log.warn("Сервис '{}' не найден в WSDL {}, используем единственный доступный сервис '{}'",
                        requestedName, request.getUrl(), fallback.name());
            }
            return fallback;
        }
        throw new WsdlEndpointResolutionException(hasText(requestedName)
                ? "Сервис '" + requestedName + "' не найден в WSDL " + request.getUrl()
                        + ", однозначный fallback невозможен: сервисов с soap:address — " + callable.size()
                : "Имя сервиса не задано, а WSDL " + request.getUrl()
                        + " содержит " + callable.size() + " сервисов с soap:address");
    }

    private WsdlPort selectPort(WsdlService service, EndpointContext request) {
        if (service.ports().isEmpty()) {
            throw new WsdlEndpointResolutionException("В WSDL " + request.getUrl()
                    + " у сервиса '" + service.name() + "' не найден порт с soap:address");
        }

        return service.ports().getFirst();
    }

    @Nullable
    private String resolveSoapAction(WsdlModel model, WsdlPort port, EndpointContext request) {
        var operationName = request.getOperationName();
        if (operationName == null) {
            return null;
        }
        Map<String, String> operations = findBindingOperations(model, port.binding());
        if (operations == null) {
            throw new WsdlEndpointResolutionException("Binding " + port.binding()
                    + " не найден в WSDL " + request.getUrl() + " (включая импорты)");
        }
        if (!operations.containsKey(operationName)) {
            throw new WsdlEndpointResolutionException("Операция '" + operationName + "' не найдена в binding "
                    + port.binding() + " WSDL " + request.getUrl()
                    + ", доступные операции: " + operations.keySet());
        }
        var soapAction = operations.get(operationName);
        if (soapAction == null) {
            log.debug("У операции '{}' в WSDL {} нет атрибута soapAction - заголовок SOAPAction не будет установлен",
                    operationName, request.getUrl());
        }

        return soapAction;
    }

    @Nullable
    private Map<String, String> findBindingOperations(WsdlModel model, WsdlQName bindingRef) {
        Map<String, String> exactMatch = model.bindings().get(bindingRef);
        if (exactMatch != null) {
            return exactMatch;
        }

        List<Map<String, String>> byLocalPart = model.bindings().entrySet().stream()
                .filter(entry ->
                        entry.getKey().localPart()
                                .equals(bindingRef.localPart()))
                .map(Map.Entry::getValue)
                .toList();

        return byLocalPart.size() == 1
                ? byLocalPart.getFirst()
                : null;
    }

    private static URI requireHttpUri(URI uri) {
        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new WsdlEndpointResolutionException(
                    "Недопустимая схема URL '" + uri + "': разрешены только http и https");
        }

        return uri;
    }

    /**
     * Фабрика создаётся на каждый парсинг: JDK {@link XMLInputFactory} не потокобезопасна
     * (в т.ч. OpenJDK XMLInputFactoryImpl при concurrent {@code createXMLStreamReader}),
     * <b>Стоимость создания пренебрежимо мала на фоне HTTP-загрузки документа.</b>
     */
    private static XMLInputFactory createSecureXmlInputFactory() {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        return factory;
    }


    private record LoadSession(WsdlResourceLoader loader, URI rootLocation, Set<URI> visited) {
    }

    private record WsdlQName(String namespaceUri, String localPart) {
        @Override
        public String toString() {
            return hasText(namespaceUri)
                    ? "{" + namespaceUri + "}" + localPart
                    : localPart;
        }
    }

    private record WsdlPort(WsdlQName binding, String address) {
    }

    private record WsdlService(@Nullable String name, List<WsdlPort> ports) {
    }

    private record WsdlDocument(@Nullable String targetNamespace,
                                List<String> imports,
                                List<WsdlService> services,
                                Map<WsdlQName, Map<String, String>> bindings) {
    }

    private record WsdlModel(@Nullable String targetNamespace,
                             List<WsdlService> services,
                             Map<WsdlQName, Map<String, String>> bindings) {
    }

    /**
     * Однопроходный парсер одного WSDL-документа: за один проход собирает targetNamespace,
     * wsdl:import, сервисы с портами (binding + soap:address строго в рамках одного тега port)
     * и SoapAction операций по каждому binding. Ранее делал 3 прохода
     */
    private static final class WsdlDocumentParser {
        private final XMLStreamReader reader;

        @Nullable
        private String targetNamespace;
        private final List<String> imports = new ArrayList<>();
        private final List<WsdlService> services = new ArrayList<>();
        private final Map<WsdlQName, Map<String, String>> bindings = new LinkedHashMap<>();

        @Nullable
        private String currentServiceName;
        @Nullable
        private List<WsdlPort> currentServicePorts;
        @Nullable
        private WsdlQName currentPortBinding;
        @Nullable
        private String currentPortAddress;

        @Nullable
        private String currentBindingName;
        @Nullable
        private Map<String, String> currentBindingOperations;
        @Nullable
        private String currentOperationName;

        private WsdlDocumentParser(XMLStreamReader reader) {
            this.reader = reader;
        }

        private WsdlDocument parse() throws XMLStreamException {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    onStartElement();
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    onEndElement();
                }
            }
            return new WsdlDocument(targetNamespace, List.copyOf(imports),
                    List.copyOf(services), unmodifiableMap(bindings));
        }

        private void onStartElement() {
            String namespace = reader.getNamespaceURI();
            if (WSDL_NAMESPACE.equals(namespace)) {
                onWsdlStartElement(reader.getLocalName());
            } else if (SOAP11_NAMESPACE.equals(namespace)
                    || SOAP12_NAMESPACE.equals(namespace)) {
                onSoapStartElement(reader.getLocalName());
            }
        }

        private void onWsdlStartElement(String localName) {
            switch (localName) {
                case "definitions" -> targetNamespace = attribute("targetNamespace");
                case "import" -> addImport(attribute("location"));
                case "service" -> beginService();
                case "port" -> beginPort();
                case "binding" -> beginBinding();
                case "operation" -> beginBindingOperation();
                default -> {
                    // empty
                }
            }
        }

        private void onSoapStartElement(String localName) {
            switch (localName) {
                case "address" -> capturePortAddress();
                case "operation" -> captureSoapAction();
                default -> {
                    // empty
                }
            }
        }

        private void onEndElement() {
            if (!WSDL_NAMESPACE.equals(reader.getNamespaceURI())) {
                return;
            }
            switch (reader.getLocalName()) {
                case "port" -> endPort();
                case "service" -> endService();
                case "operation" -> currentOperationName = null;
                case "binding" -> endBinding();
                default -> {
                    // empty
                }

            }
        }

        private void addImport(@Nullable String location) {
            if (hasText(location)) {
                imports.add(location);
            }
        }

        private void beginService() {
            currentServiceName = attribute("name");
            currentServicePorts = new ArrayList<>();
        }

        private void beginPort() {
            if (currentServicePorts == null) {
                return;
            }
            currentPortBinding =
                    resolveQName(attribute("binding"));
            currentPortAddress = null;
        }

        private void capturePortAddress() {
            if (currentPortBinding == null || currentPortAddress != null) {
                return;
            }
            var location = attribute("location");
            if (hasText(location)) {
                currentPortAddress = location;
            }
        }

        private void endPort() {
            if (currentServicePorts != null
                    && currentPortBinding != null
                    && currentPortAddress != null
            ) {
                var port = new WsdlPort(currentPortBinding, currentPortAddress);
                currentServicePorts.add(port);
            }
            currentPortBinding = null;
            currentPortAddress = null;
        }

        private void endService() {
            if (currentServicePorts != null) {
                var service =
                        new WsdlService(currentServiceName,
                                List.copyOf(currentServicePorts));
                services.add(service);
            }
            currentServiceName = null;
            currentServicePorts = null;
        }

        private void beginBinding() {
            currentBindingName = attribute("name");
            currentBindingOperations =
                    new LinkedHashMap<>();
        }

        private void beginBindingOperation() {
            // wsdl:operation внутри portType игнорируем: SoapAction есть только в binding
            if (currentBindingOperations != null) {
                currentOperationName = attribute("name");
            }
        }

        private void captureSoapAction() {
            if (currentBindingOperations != null
                    && currentOperationName != null) {
                // Значение может быть null: атрибут soapAction не обязателен
                currentBindingOperations.put(currentOperationName,
                        attribute("soapAction"));
            }
        }

        private void endBinding() {
            if (hasText(currentBindingName)
                    && currentBindingOperations != null) {
                bindings.putIfAbsent(new WsdlQName(namespaceOrEmpty(targetNamespace), currentBindingName),
                        unmodifiableMap(currentBindingOperations));
            }
            currentBindingName = null;
            currentBindingOperations = null;
        }

        @Nullable
        private WsdlQName resolveQName(@Nullable String rawQName) {
            if (!hasText(rawQName)) {
                return null;
            }
            int separator = rawQName.indexOf(':');
            var prefix = separator > 0
                    ? rawQName.substring(0, separator)
                    : XMLConstants.DEFAULT_NS_PREFIX;
            var localPart =
                    rawQName.substring(separator + 1);
            var namespaceUri =
                    reader
                            .getNamespaceContext()
                            .getNamespaceURI(prefix);

            return new WsdlQName(namespaceOrEmpty(namespaceUri), localPart);
        }

        @Nullable
        private String attribute(String name) {
            return reader.getAttributeValue(null, name);
        }

        private static String namespaceOrEmpty(@Nullable String namespaceUri) {
            return namespaceUri == null
                    ? XMLConstants.NULL_NS_URI
                    : namespaceUri;
        }
    }
}
