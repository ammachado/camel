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
package org.apache.camel.component.aws2.stepfunctions;

import org.apache.camel.component.aws2.stepfunctions.client.StepFunctions2ClientFactory;
import org.apache.camel.component.aws2.stepfunctions.client.StepFunctions2InternalClient;
import org.apache.camel.component.aws2.stepfunctions.client.impl.StepFunctions2ClientIAMOptimizedImpl;
import org.apache.camel.component.aws2.stepfunctions.client.impl.StepFunctions2ClientIAMProfileOptimizedImpl;
import org.apache.camel.component.aws2.stepfunctions.client.impl.StepFunctions2ClientSessionTokenImpl;
import org.apache.camel.component.aws2.stepfunctions.client.impl.StepFunctions2ClientStandardImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class StepFunctions2ClientFactoryTest {

    @Test
    public void getStandardSfnClientDefault() {
        StepFunctions2Configuration sfn2Configuration = new StepFunctions2Configuration();
        StepFunctions2InternalClient sfnClient = StepFunctions2ClientFactory.getSfnClient(sfn2Configuration);
        assertTrue(sfnClient instanceof StepFunctions2ClientStandardImpl);
    }

    @Test
    public void getStandardSfnClient() {
        StepFunctions2Configuration sfn2Configuration = new StepFunctions2Configuration();
        sfn2Configuration.setUseDefaultCredentialsProvider(false);
        StepFunctions2InternalClient sfnClient = StepFunctions2ClientFactory.getSfnClient(sfn2Configuration);
        assertTrue(sfnClient instanceof StepFunctions2ClientStandardImpl);
    }

    @Test
    public void getIAMOptimizedSfnClient() {
        StepFunctions2Configuration sfn2Configuration = new StepFunctions2Configuration();
        sfn2Configuration.setUseDefaultCredentialsProvider(true);
        StepFunctions2InternalClient sfnClient = StepFunctions2ClientFactory.getSfnClient(sfn2Configuration);
        assertTrue(sfnClient instanceof StepFunctions2ClientIAMOptimizedImpl);
    }

    @Test
    public void getIAMProfileOptimizedSfnClient() {
        StepFunctions2Configuration sfn2Configuration = new StepFunctions2Configuration();
        sfn2Configuration.setUseProfileCredentialsProvider(true);
        StepFunctions2InternalClient sfnClient = StepFunctions2ClientFactory.getSfnClient(sfn2Configuration);
        assertTrue(sfnClient instanceof StepFunctions2ClientIAMProfileOptimizedImpl);
    }

    @Test
    public void getSessionTokenSfnClient() {
        StepFunctions2Configuration sfn2Configuration = new StepFunctions2Configuration();
        sfn2Configuration.setUseSessionCredentials(true);
        StepFunctions2InternalClient sfnClient = StepFunctions2ClientFactory.getSfnClient(sfn2Configuration);
        assertTrue(sfnClient instanceof StepFunctions2ClientSessionTokenImpl);
    }
}
