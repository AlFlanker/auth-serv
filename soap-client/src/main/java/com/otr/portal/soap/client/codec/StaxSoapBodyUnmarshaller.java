package com.otr.portal.soap.client.codec;

import com.otr.portal.soap.client.exception.SoapCodecException;
import com.otr.portal.soap.client.exception.SoapFaultException;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;

import static javax.xml.stream.XMLStreamConstants.CDATA;
import static javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * Извлекает полезную нагрузку из SOAP Body и демаршалит её в JAXB-тип
 * за один проход StAX-парсером - без построения DOM.
 * <p>
 *
 * @author alexraspopin
 * @since 15.07.2026
 */
@UtilityClass
public final class StaxSoapBodyUnmarshaller {

    private static final String ENVELOPE_ELEMENT = "Envelope";
    private static final String BODY_ELEMENT = "Body";
    private static final String FAULT_ELEMENT = "Fault";

    private static final String SOAP11_ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12_ENVELOPE_NAMESPACE = "http://www.w3.org/2003/05/soap-envelope";

    /**
     * Демаршалит первый элемент SOAP Body конверта {@code xml} в {@code type}.
     *
     * @throws SoapFaultException если в Body лежит SOAP Fault
     * @throws SoapCodecException если XML некорректен или структура конверта не распознана
     */
    public static <T> T unmarshalBody(String xml, JAXBContext context, Class<T> type) {
        XMLStreamReader reader = null;
        try {
            reader = createSecureInputFactory()
                    .createXMLStreamReader(new StringReader(xml));
            moveToBodyPayload(reader);
            if (isSoapFault(reader)) {
                throw readFault(reader);
            }
            return context.createUnmarshaller()
                    .unmarshal(reader, type)
                    .getValue();
        } catch (XMLStreamException | JAXBException e) {
            throw new SoapCodecException("Не удалось разобрать SOAP-конверт ответа", e);
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * Доводит ридер до первого элемента внутри SOAP Body.
     */
    private static void moveToBodyPayload(XMLStreamReader reader) throws XMLStreamException {
        moveToEnvelope(reader);
        moveToBody(reader);
        if (!moveToFirstChildElement(reader)) {
            throw new SoapCodecException("Элемент ответа не найден в SOAP Body");
        }
    }

    private static void moveToEnvelope(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            if (reader.next() != START_ELEMENT) {
                continue;
            }
            if (!ENVELOPE_ELEMENT.equals(reader.getLocalName())) {
                throw new SoapCodecException(
                        "Корневой элемент ответа не SOAP Envelope: '" + reader.getLocalName() + "'");
            }
            return;
        }
        throw new SoapCodecException("SOAP Envelope не найден в ответе");
    }

    private static void moveToBody(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == START_ELEMENT) {
                if (BODY_ELEMENT.equals(reader.getLocalName())) {
                    return;
                }
                skipSubtree(reader);
            } else if (event == END_ELEMENT) {
                // закрылся Envelope
                break;
            }
        }
        throw new SoapCodecException("SOAP Body не найден в ответе");
    }

    /**
     * @return {@code true}, если ридер встал на первый дочерний элемент;
     * {@code false} - текущий элемент пуст
     */
    private static boolean moveToFirstChildElement(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == START_ELEMENT) {
                return true;
            }
            if (event == END_ELEMENT) {
                return false;
            }
        }
        return false;
    }

    /**
     * Пропускает поддерево текущего элемента до его закрывающего тега включительно.
     */
    private static void skipSubtree(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            if (event == START_ELEMENT) {
                depth++;
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
    }

    private static boolean isSoapFault(XMLStreamReader reader) {
        return FAULT_ELEMENT.equals(reader.getLocalName())
                && isSoapEnvelopeNamespace(reader.getNamespaceURI());
    }

    private static boolean isSoapEnvelopeNamespace(@Nullable String namespaceUri) {
        return SOAP11_ENVELOPE_NAMESPACE.equals(namespaceUri)
                || SOAP12_ENVELOPE_NAMESPACE.equals(namespaceUri);
    }

    /**
     * Читает поддерево {@code Fault}, вытаскивая код и текст ошибки:
     * SOAP 1.1 - {@code faultcode}/{@code faultstring}, SOAP 1.2 - {@code Code/Value}, {@code Reason/Text}.
     */
    private static SoapFaultException readFault(XMLStreamReader reader) throws XMLStreamException {
        String faultCode = null;
        String faultString = null;

        String currentElement = null;
        var text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            switch (event) {
                case START_ELEMENT -> {
                    depth++;
                    currentElement = reader.getLocalName();
                    text.setLength(0);
                }
                case CHARACTERS, CDATA -> text.append(reader.getText());
                case END_ELEMENT -> {
                    depth--;
                    var value = text.toString().trim();
                    if (currentElement != null && !value.isEmpty()) {
                        if (faultCode == null && isFaultCodeElement(currentElement)) {
                            faultCode = value;
                        } else if (faultString == null && isFaultStringElement(currentElement)) {
                            faultString = value;
                        }
                    }
                    currentElement = null;
                    text.setLength(0);
                }
                default -> {
                    // прочие события не интересны
                }
            }
        }

        return new SoapFaultException(faultCode, faultString);
    }

    private static boolean isFaultCodeElement(String localName) {
        return "faultcode".equals(localName) || "Value".equals(localName);
    }

    private static boolean isFaultStringElement(String localName) {
        return "faultstring".equals(localName) || "Text".equals(localName);
    }

    /**
     * Фабрика создаётся на каждый вызов: JDK {@link XMLInputFactory} не потокобезопасна.
     * {@link XMLInputFactory#newDefaultFactory()} возвращает встроенную реализацию JDK
     * без ServiceLoader-lookup, поэтому создание дёшево даже на горячем пути.
     */
    private static XMLInputFactory createSecureInputFactory() {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        return factory;
    }

    private static void closeQuietly(@Nullable XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // закрытие ридера поверх StringReader не может осмысленно упасть
        }
    }
}
