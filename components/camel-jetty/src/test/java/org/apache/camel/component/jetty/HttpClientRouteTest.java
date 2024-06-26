/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.jetty;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.test.junit5.TestSupport.assertIsInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpClientRouteTest extends BaseJettyTest {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientRouteTest.class);

    @Test
    public void testHttpRouteWithMessageHeader() throws Exception {
        testHttpClient("direct:start");
    }

    @Test
    public void testHttpRouteWithOption() throws Exception {
        testHttpClient("direct:start2");
    }

    private void testHttpClient(String uri) throws Exception {
        System.setProperty("HTTPClient.dontChunkRequests", "yes");

        MockEndpoint mockEndpoint = getMockEndpoint("mock:a");
        mockEndpoint.expectedBodiesReceived("<b>Hello World</b>");

        template.requestBodyAndHeader(uri, new ByteArrayInputStream("This is a test".getBytes()), "Content-Type",
                "application/xml");

        mockEndpoint.assertIsSatisfied();
        List<Exchange> list = mockEndpoint.getReceivedExchanges();
        Exchange exchange = list.get(0);
        assertNotNull(exchange, "exchange");

        Message in = exchange.getIn();
        assertNotNull(in, "in");

        Map<String, Object> headers = in.getHeaders();

        LOG.info("Headers: {}", headers);

        assertFalse(headers.isEmpty(), "Should be more than one header but was: " + headers);
    }

    @Test
    public void testHttpRouteWithQuery() throws Exception {
        MockEndpoint mockEndpoint = getMockEndpoint("mock:a");
        mockEndpoint.expectedBodiesReceived("@ query");

        template.sendBody("direct:start3", null);
        mockEndpoint.assertIsSatisfied();
    }

    @Test
    public void testHttpRouteWithQueryByHeader() throws Exception {
        MockEndpoint mockEndpoint = getMockEndpoint("mock:a");
        mockEndpoint.expectedBodiesReceived("test");

        template.sendBody("direct:start4", "test");
        mockEndpoint.assertIsSatisfied();
    }

    @Test
    public void testHttpRouteWithHttpURI() {
        Exchange exchange = template.send("http://localhost:" + port2 + "/querystring", new Processor() {
            @Override
            public void process(Exchange exchange) {
                exchange.getIn().setBody("");
                exchange.getIn().setHeader(Exchange.HTTP_URI, "http://localhost:" + port2 + "/querystring?id=test");
            }
        });
        assertEquals("test", exchange.getMessage().getBody(String.class), "Get a wrong response.");
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                errorHandler(noErrorHandler());

                Processor clientProc = new Processor() {
                    public void process(Exchange exchange) {
                        // small payloads is optimized to be byte array for http component
                        assertIsInstanceOf(byte[].class, exchange.getIn().getBody());
                    }
                };

                from("direct:start").to("http://localhost:" + port1 + "/hello").process(clientProc).convertBodyTo(String.class)
                        .to("mock:a");
                from("direct:start2").to("http://localhost:" + port2 + "/hello").to("mock:a");
                from("direct:start3").to("http://localhost:" + port2 + "/Query%20/test?myQuery=%40%20query").to("mock:a");
                from("direct:start4").setHeader(Exchange.HTTP_QUERY, simple("id=${body}"))
                        .to("http://localhost:" + port2 + "/querystring").to("mock:a");

                Processor proc = new Processor() {
                    public void process(Exchange exchange) {
                        ByteArrayInputStream bis = new ByteArrayInputStream("<b>Hello World</b>".getBytes());
                        exchange.getMessage().setBody(bis);
                    }
                };
                from("jetty:http://localhost:" + port1 + "/hello").process(proc).setHeader(Exchange.HTTP_CHUNKED)
                        .constant(false);

                from("jetty:http://localhost:" + port2 + "/hello?chunked=false").process(proc);

                from("jetty:http://localhost:" + port2 + "/Query%20/test").process(new Processor() {
                    public void process(Exchange exchange) {
                        exchange.getMessage().setBody(exchange.getIn().getHeader("myQuery", String.class));
                    }
                });

                from("jetty:http://localhost:" + port2 + "/querystring").process(new Processor() {
                    public void process(Exchange exchange) {
                        String result = exchange.getIn().getHeader("id", String.class);
                        if (result == null) {
                            result = "No id header";
                        }
                        exchange.getMessage().setBody(result);
                    }
                });
            }
        };
    }

}
