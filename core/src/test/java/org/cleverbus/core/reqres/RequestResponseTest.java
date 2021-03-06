/*
 * Copyright (C) 2015
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cleverbus.core.reqres;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.springframework.util.StringUtils.hasText;

import java.util.Date;
import java.util.List;

import javax.annotation.Nullable;
import javax.persistence.TypedQuery;
import javax.xml.namespace.QName;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPEnvelope;
import javax.xml.soap.SOAPFault;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPPart;

import org.apache.commons.codec.binary.Hex;
import org.cleverbus.api.asynch.AsynchConstants;
import org.cleverbus.api.entity.Message;
import org.cleverbus.api.entity.MsgStateEnum;
import org.cleverbus.api.entity.Request;
import org.cleverbus.api.entity.Response;
import org.cleverbus.core.AbstractCoreDbTest;
import org.cleverbus.test.ExternalSystemTestEnum;
import org.cleverbus.test.ServiceTestEnum;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.custommonkey.xmlunit.Diff;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessage;


/**
 * Test suite for {@link RequestSendingEventNotifier} and {@link ResponseReceiveEventNotifier}.
 *
 * @author <a href="mailto:petr.juza@cleverlance.com">Petr Juza</a>
 */
public class RequestResponseTest extends AbstractCoreDbTest {

    private static final String REQUEST = "request";

    private static final String RESPONSE = "response";

    private static final String TARGET_URI = "direct://target";

    @Produce(uri = "direct:start")
    private ProducerTemplate producer;

    @EndpointInject(uri = "mock:test")
    private MockEndpoint mock;

    @Autowired
    private RequestSendingEventNotifier reqSendingEventNotifier;

    @Autowired
    private ResponseReceiveEventNotifier resReceiveEventNotifier;


    @Before
    public void prepareConfiguration() {
        setPrivateField(reqSendingEventNotifier, "enable", Boolean.TRUE);
        setPrivateField(reqSendingEventNotifier, "endpointFilter", "^(direct.*target).*$");
        reqSendingEventNotifier.compilePattern();

        setPrivateField(resReceiveEventNotifier, "enable", Boolean.TRUE);
        setPrivateField(resReceiveEventNotifier, "endpointFilter", "^(direct.*target).*$");
        resReceiveEventNotifier.compilePattern();
    }

    @Before
    public void prepareRoute() throws Exception {
        RouteBuilder route = new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start")
                        .to(TARGET_URI)
                        .to("mock:test");
            }
        };

        getCamelContext().addRoutes(route);
    }

    /**
     * Test saving synchronous request/response with correct calling target endpoint.
     * Then also verify two scenarios - change pattern for filtering URI and disable saving mechanism at all.
     */
    @Test
    public void testSavingRequest() throws Exception {
        // prepare target route
        prepareTargetRoute(TARGET_URI, null);

        // action
        mock.expectedMessageCount(1);
        final byte[] byteBody = {(byte) 0xe0, 0x4f, (byte) 0xd0, 0x20};
        producer.sendBody(byteBody);

        assertRequestResponse(Hex.encodeHexString(byteBody), null, null, null);


        // try it again but change pattern for filtering
        setPrivateField(reqSendingEventNotifier, "endpointFilter", "^(noUrl).*$");
        reqSendingEventNotifier.compilePattern();

        setPrivateField(resReceiveEventNotifier, "endpointFilter", "^(noUrl).*$");
        resReceiveEventNotifier.compilePattern();

        producer.sendBody(REQUEST);

        TypedQuery<Request> queryReq = em.createQuery("FROM " + Request.class.getName(), Request.class);
        List<Request> requests = queryReq.getResultList();
        assertThat(requests.size(), is(1));

        TypedQuery<Response> queryRes = em.createQuery("FROM " + Response.class.getName(), Response.class);
        List<Response> responses = queryRes.getResultList();
        assertThat(responses.size(), is(1));


        // try it again but disable saving at all
        setPrivateField(reqSendingEventNotifier, "enable", Boolean.FALSE);
        setPrivateField(resReceiveEventNotifier, "enable", Boolean.FALSE);

        producer.sendBody(REQUEST);

        queryReq = em.createQuery("FROM " + Request.class.getName(), Request.class);
        requests = queryReq.getResultList();
        assertThat(requests.size(), is(1));

        queryRes = em.createQuery("FROM " + Response.class.getName(), Response.class);
        responses = queryRes.getResultList();
        assertThat(responses.size(), is(1));

        // identical response
        assertThat(responses.get(0), is(requests.get(0).getResponse()));
    }

    /**
     * Test saving response only, there is no request.
     */
    @Test
    public void testSavingResponseOnly() throws Exception {
        setPrivateField(reqSendingEventNotifier, "enable", Boolean.FALSE);

        // prepare target route
        prepareTargetRoute(TARGET_URI, null);

        // action
        mock.expectedMessageCount(1);
        producer.sendBody(REQUEST);

        // verify
        TypedQuery<Request> queryReq = em.createQuery("FROM " + Request.class.getName(), Request.class);
        List<Request> requests = queryReq.getResultList();
        assertThat(requests.size(), is(0));

        TypedQuery<Response> queryRes = em.createQuery("FROM " + Response.class.getName(), Response.class);
        List<Response> responses = queryRes.getResultList();
        assertThat(responses.size(), is(1));
    }

    /**
     * Test saving response with message only, there is no request.
     */
    @Test
    @Transactional
    public void testSavingResponseWithMsgOnly() throws Exception {
        setPrivateField(reqSendingEventNotifier, "enable", Boolean.FALSE);

        // prepare target route
        prepareTargetRoute(TARGET_URI, null);

        // action
        mock.expectedMessageCount(1);

        Message msg = insertMessage();

        producer.sendBodyAndHeader(REQUEST, AsynchConstants.MSG_HEADER, msg);

        // verify
        TypedQuery<Response> queryRes = em.createQuery("FROM " + Response.class.getName(), Response.class);
        List<Response> responses = queryRes.getResultList();
        assertThat(responses.size(), is(1));
    }

    /**
     * Test saving asynchronous request/response with correct calling target endpoint.
     */
    @Test
    @Transactional
    public void testSavingRequestWithAsynchMessage() throws Exception {
        // prepare target route
        prepareTargetRoute(TARGET_URI, null);

        // action
        mock.expectedMessageCount(1);

        Message msg = insertMessage();

        producer.sendBodyAndHeader(REQUEST, AsynchConstants.MSG_HEADER, msg);

        assertRequestResponse(REQUEST, msg, null, null);
    }

    private Message insertMessage() {
        Message msg = new Message();
        msg.setState(MsgStateEnum.PROCESSING);
        msg.setMsgTimestamp(new Date());
        msg.setReceiveTimestamp(new Date());
        msg.setSourceSystem(ExternalSystemTestEnum.CRM);
        msg.setCorrelationId("123-456");
        msg.setService(ServiceTestEnum.CUSTOMER);
        msg.setOperationName("setCustomer");
        msg.setPayload("request");
        msg.setLastUpdateTimestamp(new Date());
        em.persist(msg);
        em.flush();
        return msg;
    }

    /**
     * Test saving synchronous request/response with correct calling target endpoint
     * but with different exchange (SAVE_REQ_HEADER is removed).
     */
    @Test
    public void testSavingRequestWithDifferentExchange() throws Exception {
        // prepare target route
        prepareTargetRoute(TARGET_URI, null);

        // action
        mock.expectedMessageCount(1);
        producer.sendBody(REQUEST);

        assertRequestResponse(REQUEST, null, null, null);
    }

    /**
     * Test saving synchronous request/response where response is failed "normal" exception.
     */
    @Test
    public void testSavingRequestWithFailedResponse() throws Exception {
        // prepare target route
        prepareTargetRoute(TARGET_URI, new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.setException(new IllegalStateException("err"));
            }
        });

        // action
        mock.expectedMessageCount(0);

        try {
            producer.sendBody(REQUEST);
            fail("Target route was thrown exception.");
        } catch (CamelExecutionException ex) {
            assertRequestResponse(REQUEST, null, "java.lang.IllegalStateException: err", null);
        }
    }

    /**
     * Test saving synchronous request/response where response is failed SOAP fault exception.
     */
    @Test
    public void testSavingRequestWithSOAPFaultResponse() throws Exception {

        final String soapFault = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Fault xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "     <faultcode>SOAP-ENV:Server</faultcode>"
                + "     <faultstring>There is one error</faultstring>"
                + "</SOAP-ENV:Fault>";

        // prepare target route
        prepareTargetRoute(TARGET_URI, new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getOut().setBody(soapFault);

                // create SOAP Fault message
                SOAPMessage soapMessage = MessageFactory.newInstance().createMessage();
                SOAPPart soapPart = soapMessage.getSOAPPart();
                SOAPEnvelope soapEnvelope = soapPart.getEnvelope();
                SOAPBody soapBody = soapEnvelope.getBody();

                // Set fault code and fault string
                SOAPFault fault = soapBody.addFault();
                fault.setFaultCode(new QName("http://schemas.xmlsoap.org/soap/envelope/", "Server"));
                fault.setFaultString("There is one error");
                SoapMessage message = new SaajSoapMessage(soapMessage);
                throw new SoapFaultClientException(message);
            }
        });

        // action
        mock.expectedMessageCount(0);

        try {
            producer.sendBody(REQUEST);
            fail("Target route was thrown exception.");
        } catch (CamelExecutionException ex) {
            assertRequestResponse(REQUEST, null,
                    "org.springframework.ws.soap.client.SoapFaultClientException: There is one error", soapFault);
        }
    }

    /**
     * Prepares target route to test saving of requests and responses from external system.
     *
     * @param targetUri the target URI
     * @param processor the callback response processor
     * @throws Exception
     */
    private void prepareTargetRoute(final String targetUri, final @Nullable Processor processor) throws Exception {
        Assert.hasText(targetUri, "the targetUri must not be empty");

        RouteBuilder route = new RouteBuilder() {
            @Override
            public void configure() throws Exception {

                Processor callbackProcessor = processor;

                if (callbackProcessor == null) {
                    callbackProcessor = new Processor() {
                        @Override
                        public void process(Exchange exchange) throws Exception {
                            // result of communication with external system is in OUT part of Message
                            exchange.getOut().setBody(RESPONSE);
                        }
                    };
                }

                from(targetUri)
                        // simulates changes in exchange - it must be irrelevant
                        .removeHeader(RequestSendingEventNotifier.SAVE_REQ_HEADER)
                        .process(callbackProcessor);
            }
        };

        getCamelContext().addRoutes(route);
    }

    private void assertRequestResponse(String requestBody, @Nullable Message msg, @Nullable String failedRes,
                        @Nullable String resEnvelope) throws Exception {

        mock.assertIsSatisfied();

        Exchange exchange = null;
        if (!mock.getExchanges().isEmpty()) {
            exchange = mock.getExchanges().get(0);
        }

        // verify request/response in DB
        TypedQuery<Request> queryReq = em.createQuery("FROM " + Request.class.getName(), Request.class);
        List<Request> requests = queryReq.getResultList();

        assertThat(requests.size(), is(1));
        Request request = requests.get(0);
        assertThat(request.getUri(), is(TARGET_URI));
        assertThat(request.getRequest(), is(requestBody));
        assertThat(request.getMessage(), is(msg));
        if (msg != null) {
            assertThat(request.getResponseJoinId(), is(msg.getCorrelationId()));
        } else if (exchange != null) {
            assertThat(request.getResponseJoinId(), is(exchange.getExchangeId()));
        }

        TypedQuery<Response> queryRes = em.createQuery("FROM " + Response.class.getName(), Response.class);
        List<Response> responses = queryRes.getResultList();

        assertThat(responses.size(), is(1));
        Response response = responses.get(0);
        if (failedRes == null) {
            assertThat(response.isFailed(), is(false));
            assertThat(response.getResponse(), is(RESPONSE));
        } else {
            assertThat(response.isFailed(), is(true));

            if (hasText(resEnvelope)) {
                Diff myDiff = new Diff(response.getResponse(), resEnvelope);
                assertTrue("XML similar " + myDiff.toString(), myDiff.similar());
            } else {
                assertThat(response.getResponse(), nullValue());
            }

            assertThat(response.getFailedReason(), startsWith(failedRes));
        }

        assertThat(response.getRequest(), is(request));
    }
}
