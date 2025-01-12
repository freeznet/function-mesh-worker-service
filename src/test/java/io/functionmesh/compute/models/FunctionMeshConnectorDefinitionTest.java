/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.functionmesh.compute.models;

import org.junit.Assert;
import org.junit.Test;

public class FunctionMeshConnectorDefinitionTest {
    @Test
    public void testFunctionMeshConnectorDefinition() {
        FunctionMeshConnectorDefinition functionMeshConnectorDefinition = new FunctionMeshConnectorDefinition();
        functionMeshConnectorDefinition.setId("pulsar-io-test");
        functionMeshConnectorDefinition.setName("test");
        functionMeshConnectorDefinition.setVersion("2.7.0");
        functionMeshConnectorDefinition.setImageTag("2.7.0");
        functionMeshConnectorDefinition.setImageRepository("streamnative/pulsar-io-test");
        Assert.assertEquals(functionMeshConnectorDefinition.getId(), "pulsar-io-test");
        Assert.assertEquals(functionMeshConnectorDefinition.getName(), "test");
        Assert.assertEquals(functionMeshConnectorDefinition.getVersion(), "2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getImageTag(), "2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getImageRepository(), "streamnative/pulsar-io-test");
        Assert.assertEquals(functionMeshConnectorDefinition.toFullImageURL(),
                "docker.io/streamnative/pulsar-io-test:2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getJar(), "connectors/pulsar-io-test-2.7.0.nar");
    }

    @Test
    public void testFunctionMeshConnectorDefinitionTestCase() {
        FunctionMeshConnectorDefinition functionMeshConnectorDefinition = new FunctionMeshConnectorDefinition();
        functionMeshConnectorDefinition.setId("pulsar-io-test");
        functionMeshConnectorDefinition.setName("test");
        functionMeshConnectorDefinition.setVersion("2.7.0");
        functionMeshConnectorDefinition.setImageTag("2.7.0");
        functionMeshConnectorDefinition.setImageRepository("privateregistry.io/streamnative/pulsar-io-test");
        functionMeshConnectorDefinition.setImageRegistry("");
        Assert.assertEquals(functionMeshConnectorDefinition.getId(), "pulsar-io-test");
        Assert.assertEquals(functionMeshConnectorDefinition.getName(), "test");
        Assert.assertEquals(functionMeshConnectorDefinition.getVersion(), "2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getImageTag(), "2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getImageRepository(),
                "privateregistry.io/streamnative/pulsar-io-test");
        Assert.assertEquals(functionMeshConnectorDefinition.toFullImageURL(),
                "privateregistry.io/streamnative/pulsar-io-test:2.7.0");
        Assert.assertEquals(functionMeshConnectorDefinition.getJar(), "connectors/pulsar-io-test-2.7.0.nar");
    }
    
    @Test
    public void testFunctionMeshConnectorDefinitionTestJarFullName() {
        FunctionMeshConnectorDefinition functionMeshConnectorDefinition = new FunctionMeshConnectorDefinition();
        functionMeshConnectorDefinition.setId("pulsar-io-test");
        functionMeshConnectorDefinition.setName("test");
        functionMeshConnectorDefinition.setVersion("2.7.0");
        functionMeshConnectorDefinition.setImageTag("2.7.0");
        functionMeshConnectorDefinition.setImageRepository("privateregistry.io/streamnative/pulsar-io-test");
        functionMeshConnectorDefinition.setImageRegistry("");
        Assert.assertEquals(functionMeshConnectorDefinition.getJar(), "connectors/pulsar-io-test-2.7.0.nar");

        functionMeshConnectorDefinition.setJarFullName("");
        Assert.assertEquals(functionMeshConnectorDefinition.getJar(), "connectors/pulsar-io-test-2.7.0.nar");

        functionMeshConnectorDefinition.setJarFullName("pulsar-io-test-2.7.0.jar");
        Assert.assertEquals(functionMeshConnectorDefinition.getJar(), "connectors/pulsar-io-test-2.7.0.jar");

    }
}
