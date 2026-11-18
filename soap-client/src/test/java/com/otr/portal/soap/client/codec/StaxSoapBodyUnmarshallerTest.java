package com.otr.portal.soap.client.codec;

import com.otr.portal.soap.client.exception.SoapCodecException;
import com.otr.portal.soap.client.exception.SoapFaultException;
import org.junit.jupiter.api.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaxSoapBodyUnmarshallerTest {

    private static final JAXBContext CONTEXT = createContext();

    private static JAXBContext createContext() {
        try {
            return JAXBContext.newInstance(TestReply.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unmarshalBodyExtractsPayloadAndSkipsHeader() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Header>
                        <sec:security-context xmlns:sec="urn:sec">
                            <sec:user>john.doe</sec:user>
                        </sec:security-context>
                    </soapenv:Header>
                    <soapenv:Body>
                        <t:testReply xmlns:t="urn:test">
                            <message>hello</message>
                            <count>42</count>
                        </t:testReply>
                    </soapenv:Body>
                </soapenv:Envelope>""";

        var reply = StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class);

        assertThat(reply.message).isEqualTo("hello");
        assertThat(reply.count).isEqualTo(42);
    }

    @Test
    void unmarshalBodyAcceptsSoap12Envelope() {
        var xml = """
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                    <env:Body>
                        <t:testReply xmlns:t="urn:test"><message>soap12</message><count>1</count></t:testReply>
                    </env:Body>
                </env:Envelope>""";

        var reply = StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class);

        assertThat(reply.message).isEqualTo("soap12");
    }

    @Test
    void unmarshalBodyThrowsOnSoap11Fault() {
        var xml = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body>
                        <soapenv:Fault>
                            <faultcode>soapenv:Server</faultcode>
                            <faultstring>Внутренняя ошибка сервиса</faultstring>
                        </soapenv:Fault>
                    </soapenv:Body>
                </soapenv:Envelope>""";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOfSatisfying(SoapFaultException.class, fault -> {
                    assertThat(fault.getFaultCode()).isEqualTo("soapenv:Server");
                    assertThat(fault.getFaultString()).isEqualTo("Внутренняя ошибка сервиса");
                });
    }

    @Test
    void unmarshalBodyThrowsOnSoap12Fault() {
        var xml = """
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                    <env:Body>
                        <env:Fault>
                            <env:Code><env:Value>env:Receiver</env:Value></env:Code>
                            <env:Reason><env:Text xml:lang="ru">Отказ обработчика</env:Text></env:Reason>
                        </env:Fault>
                    </env:Body>
                </env:Envelope>""";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOfSatisfying(SoapFaultException.class, fault -> {
                    assertThat(fault.getFaultCode()).isEqualTo("env:Receiver");
                    assertThat(fault.getFaultString()).isEqualTo("Отказ обработчика");
                });
    }

    @Test
    void unmarshalBodyFailsWhenBodyMissing() {
        var xml = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Header/>
                </soapenv:Envelope>""";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOf(SoapCodecException.class)
                .hasMessageContaining("SOAP Body");
    }

    @Test
    void unmarshalBodyFailsWhenBodyEmpty() {
        var xml = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body/>
                </soapenv:Envelope>""";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOf(SoapCodecException.class)
                .hasMessageContaining("Элемент ответа не найден");
    }

    @Test
    void unmarshalBodyFailsWhenRootIsNotEnvelope() {
        var xml = "<html><body>gateway error</body></html>";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOf(SoapCodecException.class)
                .hasMessageContaining("не SOAP Envelope");
    }

    @Test
    void unmarshalBodyRejectsDoctype() {
        var xml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body>
                        <t:testReply xmlns:t="urn:test"><message>&xxe;</message><count>0</count></t:testReply>
                    </soapenv:Body>
                </soapenv:Envelope>""";

        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody(xml, CONTEXT, TestReply.class))
                .isInstanceOf(SoapCodecException.class);
    }

    @Test
    void unmarshalBodyFailsOnMalformedXml() {
        assertThatThrownBy(() -> StaxSoapBodyUnmarshaller.unmarshalBody("<broken", CONTEXT, TestReply.class))
                .isInstanceOf(SoapCodecException.class);
    }

    @Test
    void marshalProducesFragmentWithoutXmlDeclaration() {
        var payload = new TestReply();
        payload.message = "ping";
        payload.count = 7;

        var fragment = JaxbFragmentMarshaller.marshal(CONTEXT, "urn:test", "testReply",
                TestReply.class, payload);

        assertThat(fragment)
                .doesNotContain("<?xml")
                .contains("testReply")
                .contains("urn:test")
                .contains("ping");
    }

    @Test
    void marshalWritesDirectlyIntoWriter() {
        var payload = new TestReply();
        payload.message = "streamed";

        var out = new java.io.StringWriter();
        out.write("<prefix>");
        JaxbFragmentMarshaller.marshal(CONTEXT, "urn:test", "testReply", TestReply.class, payload, out);
        out.write("</prefix>");

        assertThat(out.toString())
                .startsWith("<prefix>")
                .endsWith("</prefix>")
                .contains("streamed");
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class TestReply {
        @XmlElement(name = "message")
        String message;
        @XmlElement(name = "count")
        int count;
    }
}
