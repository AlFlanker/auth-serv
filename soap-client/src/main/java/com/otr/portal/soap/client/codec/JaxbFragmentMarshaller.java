package com.otr.portal.soap.client.codec;

import com.otr.portal.soap.client.exception.SoapCodecException;
import lombok.experimental.UtilityClass;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import java.io.StringWriter;
import java.io.Writer;

import static javax.xml.bind.Marshaller.JAXB_FRAGMENT;

/**
 * Маршалит JAXB-объект в XML-фрагмент (без XML-декларации) - для тела и заголовков SOAP.
 * <p>
 * Классы SOAP-моделей обычно не имеют {@code @XmlRootElement}, поэтому объект
 * оборачивается в {@link JAXBElement} с именем {@code {namespace}localName}.
 * <p>
 * Потокобезопасно: {@link Marshaller} не потокобезопасен и создаётся на каждый вызов
 * (из переданного {@link JAXBContext} это дёшево); контекстом владеет вызывающий код.
 *
 * @author alexraspopin
 * @since 15.07.2026
 */
@UtilityClass
public final class JaxbFragmentMarshaller {

    /**
     * Маршалит {@code payload} в строку-фрагмент.
     */
    public static <T> String marshal(JAXBContext context, String namespace, String localName,
                                     Class<T> type, T payload) {
        var writer = new StringWriter();
        marshal(context, namespace, localName, type, payload, writer);

        return writer.toString();
    }

    /**
     * Маршалит {@code payload} прямо в {@code out} - позволяет собирать конверт
     * без промежуточной копии тела.
     */
    public static <T> void marshal(JAXBContext context, String namespace, String localName,
                                   Class<T> type, T payload, Writer out) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(JAXB_FRAGMENT, true);

            var element = new JAXBElement<>(new QName(namespace, localName), type, payload);
            marshaller.marshal(element, out);
        } catch (JAXBException e) {
            throw new SoapCodecException("Не удалось сериализовать SOAP-элемент '" + localName + "'", e);
        }
    }
}
