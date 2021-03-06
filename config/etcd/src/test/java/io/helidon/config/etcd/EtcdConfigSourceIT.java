/*
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.config.etcd;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.helidon.config.Config;
import io.helidon.config.etcd.EtcdConfigSourceBuilder.EtcdApi;
import io.helidon.config.etcd.internal.client.EtcdClient;
import io.helidon.config.etcd.internal.client.EtcdUtils;
import io.helidon.config.hocon.internal.HoconConfigParser;

import com.google.common.io.Files;
import io.helidon.common.reactive.Flow;

import static io.helidon.config.etcd.EtcdConfigSourceTest.MEDIA_TYPE_APPLICATION_HOCON;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.hamcrest.core.Is.is;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests {@link EtcdConfigSource} with both version, {@link EtcdApi#v2} and {@link EtcdApi#v3}.
 */
public class EtcdConfigSourceIT {

    private static final URI DEFAULT_URI = URI.create("http://localhost:2379");

    @ParameterizedTest
    @EnumSource(EtcdApi.class)
    public void testConfig(EtcdApi version) throws Exception {
        putConfiguration(EtcdUtils.getClientClass(version), "/application.conf");
        Config config = Config.builder()
                .sources(EtcdConfigSourceBuilder
                                 .from(DEFAULT_URI, "configuration", version)
                                 .mediaType(MEDIA_TYPE_APPLICATION_HOCON)
                                 .build())
                .addParser(new HoconConfigParser())
                .build();

        assertThat(config.get("security").asNodeList().size(), is(1));
    }

    @ParameterizedTest
    @EnumSource(EtcdApi.class)
    public void testConfigChanges(EtcdApi version) throws Exception {
        putConfiguration(EtcdUtils.getClientClass(version), "/application.conf");
        Config config = Config.builder()
                .sources(EtcdConfigSourceBuilder
                                 .from(DEFAULT_URI, "configuration", version)
                                 .mediaType(MEDIA_TYPE_APPLICATION_HOCON)
                                 .pollingStrategy(EtcdWatchPollingStrategy::new)
                                 .build())
                .addParser(new HoconConfigParser())
                .build();

        assertThat(config.get("security").asNodeList().size(), is(1));

        CountDownLatch initLatch = new CountDownLatch(1);
        CountDownLatch nextLatch = new CountDownLatch(3);

        config.changes().subscribe(new Flow.Subscriber<Config>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
                initLatch.countDown();
            }

            @Override
            public void onNext(Config item) {
                nextLatch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        assertThat(initLatch.await(1, TimeUnit.SECONDS), is(true));

        putConfiguration(EtcdUtils.getClientClass(version), "/application2.conf");
        TimeUnit.MILLISECONDS.sleep(10);
        putConfiguration(EtcdUtils.getClientClass(version), "/application3.conf");
        TimeUnit.MILLISECONDS.sleep(10);
        putConfiguration(EtcdUtils.getClientClass(version), "/application4.conf");

        assertThat(nextLatch.await(20, TimeUnit.SECONDS), is(true));
    }

    private static void putConfiguration(Class<? extends EtcdClient> etcdClientClass, String resourcePath) throws Exception {
        EtcdClient etcd = etcdClientClass.getDeclaredConstructor(URI.class).newInstance(DEFAULT_URI);

        File file = new File(EtcdConfigSourceIT.class.getResource(resourcePath).getFile());
        etcd.put("configuration", Files.readLines(file, Charset.defaultCharset()).stream().collect(Collectors.joining("\n")));
        etcd.close();
    }
}
