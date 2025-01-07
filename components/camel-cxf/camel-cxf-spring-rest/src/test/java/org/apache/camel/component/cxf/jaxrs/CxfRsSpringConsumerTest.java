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
package org.apache.camel.component.cxf.jaxrs;

import org.apache.camel.Processor;
import org.apache.camel.builder.NoErrorHandlerBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cxf.common.CXFTestSupport;
import org.apache.camel.component.cxf.jaxrs.testbean.CustomException;
import org.apache.camel.component.cxf.jaxrs.testbean.Customer;
import org.apache.camel.test.spring.junit5.CamelSpringTestSupport;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CxfRsSpringConsumerTest extends CamelSpringTestSupport {
    private static final int CXF_PORT = CXFTestSupport.getPort1();

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        final Processor testProcessor = exchange -> {
            // just throw the CustomException here
            throw new CustomException("Here is the exception");
        };
        final Processor responseProcessor = exchange -> {
            // do something else with the request properties as usual
            // do something else with the response
            exchange.getMessage().getBody(Customer.class).setId(246);
        };
        return new RouteBuilder() {
            public void configure() {
                errorHandler(new NoErrorHandlerBuilder());
                from("cxfrs://bean://rsServer1").process(testProcessor);
                from("cxfrs://bean://rsServer2").process(testProcessor);
                from("cxfrs://bean://rsServer3").process(testProcessor);
                from("cxfrs://bean://rsServerInvoke?performInvocation=true").process(responseProcessor);
            }
        };
    }

    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("org/apache/camel/component/cxf/jaxrs/CxfRsSpringConsumer.xml");
    }

    static Object[][] testMappingExceptionArgs() {
        return new Object[][] {
                new Object[] { "CxfRsSpringConsumerTest1", false },
                new Object[] { "CxfRsSpringConsumerTest2", false },
                new Object[] { "CxfRsSpringConsumerTest3", true }
        };
    }

    @Test
    void testInvokeCxfRsConsumer() {
        String address = "http://localhost:" + CXF_PORT + "/CxfRsSpringConsumerInvokeService/customerservice/customers/123";
        WebClient wc = WebClient.create(address);
        Customer c = wc.accept("application/json").get(Customer.class);
        assertEquals(246L, c.getId());
    }

    @MethodSource("testMappingExceptionArgs")
    @ParameterizedTest
    void testMappingException(String servicePath, boolean gzip) throws Exception {
        String address = "http://localhost:%d/%s/customerservice/customers/126".formatted(CXF_PORT, servicePath);
        HttpGet get = new HttpGet(address);
        get.addHeader("Accept", "application/json");
        if (gzip) {
            get.addHeader("Accept-Encoding", "gzip");
        }

        try (CloseableHttpClient httpclient = HttpClientBuilder.create().build();
             CloseableHttpResponse response = httpclient.execute(get)) {
            assertEquals(500, response.getCode(), "Get a wrong status code");
            assertEquals("exception: Here is the exception", response.getHeaders("exception")[0].toString(),
                    "Get a wrong message header");
        }
    }

}
