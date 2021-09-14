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

package org.apache.camel.component.kafka.consumer.support;

import org.apache.camel.spi.StateRepository;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public final class ResumeStrategyFactory {
    /**
     * A NO-OP resume strategy that does nothing (i.e.: no resume)
     */
    private static class NoOpResumeStrategy implements ResumeStrategy {
        @Override
        public void resume() {

        }
    }

    private static final NoOpResumeStrategy NO_OP_RESUME_STRATEGY = new NoOpResumeStrategy();

    private ResumeStrategyFactory() {
    }

    public static ResumeStrategy newResumeStrategy(
            KafkaConsumer<?, ?> consumer, StateRepository<String, String> offsetRepository,
            String seekTo) {
        if (offsetRepository != null) {
            return new OffsetResumeStrategy(consumer, offsetRepository);
        } else if (seekTo != null) {
            return new SeekPolicyResumeStrategy(consumer, seekTo);
        }

        return NO_OP_RESUME_STRATEGY;
    }
}
