/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
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

package org.kie.server.springboot.samples;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;

import static java.util.Collections.singletonMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.instance.ProcessInstance;
import org.kie.server.client.KieServicesClient;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.QueryServicesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListImagesCmd;


@Testcontainers(disabledWithoutDocker=true)
public class SpringbootImageBuilderSystemTest{

    static final String ARTIFACT_ID = "evaluation";
    static final String GROUP_ID = "org.kie.server.springboot.samples";
    static final String VERSION_1 = "1.0.0";
    static final String VERSION_2 = "2.0.0";
    
    public static final String DEFAULT_USER = "kieserver";
    public static final String DEFAULT_PASSWORD = "kieserver1!";
    
    private static final Logger logger = LoggerFactory.getLogger(SpringbootImageBuilderSystemTest.class);
    
    public static String containerId = GROUP_ID+":"+ARTIFACT_ID+":";
    
   
    private static KieServicesClient ksClient;

    
    private static ProcessServicesClient processClient;
       
    @Container
    static PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>(System.getProperty("org.kie.samples.image.postgresql","postgres:13.2"))
            .withDatabaseName("rhpamdatabase")
            .withUsername("rhpamuser")
            .withPassword("rhpampassword").withNetwork(Network.SHARED)
            .withFileSystemBind("./src/test/resources/etc/postgresql", "/docker-entrypoint-initdb.d",
                    BindMode.READ_ONLY)
            .withNetworkAliases("db");
    
    @Container
    static SpringbootKieContainer springboot = new SpringbootKieContainer();
        
    
    @BeforeAll
    static void startTestContainers() {
        assumeTrue(isDockerAvailable());
    }

    @AfterAll
    static void tearDown() throws Exception {
        springboot.stop();
        // It can be useful to dive into the image
        if (Boolean.getBoolean("noRemove")) {
        	logger.info("Skipping image removal...");
        	return;
        }
        
        DockerClient docker = DockerClientFactory.instance().client();
        ListImagesCmd listImagesCmd = docker.listImagesCmd();
        listImagesCmd.getFilters().put("reference", Arrays.asList("local/kie-server-springboot"));
        listImagesCmd.exec().stream()
         .filter(c -> c.getId() != null)
         .forEach(c -> docker.removeImageCmd(c.getId()).withForce(true).exec());
    }

    @BeforeEach
    void setup() {
        
        logger.info("KIE_SPRINGBOOT started at "+springboot.getContainerIpAddress()+":"+springboot.getFirstMappedPort());
        
        ksClient = authenticate(springboot.getContainerIpAddress()+":"+springboot.getFirstMappedPort(), DEFAULT_USER, DEFAULT_PASSWORD);
        processClient = ksClient.getServicesClient(ProcessServicesClient.class);
        
        try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        
    }
    
    @Test
    @DisplayName("user starts process in autoscan deployed container")
    @Timeout(120)
    void startProcessInContainers() throws Exception {
    	Long pid1 = null;
    	Long pid2 = null;
    	try {
    	  logger.info(" >>> startProcess................" + containerId+VERSION_1);
    	  pid1 = startProcess(VERSION_1, "evaluation");
    	  logger.info(" >>> startProcess................" + containerId+VERSION_2);
    	  pid2 = startProcess(VERSION_2, "evaluation");
    	} finally {
    		if (pid1 != null)
    		  abortProcess(VERSION_1, pid1);
    		if (pid2 != null)
      		  abortProcess(VERSION_2, pid2);   		
    	}
    }
    
    private static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }
      
    private static KieServicesClient authenticate(String host, String user, String password) {
        String serverUrl = "http://" + host + "/kie-server/services/rest/server";
        KieServicesConfiguration configuration = KieServicesFactory.newRestConfiguration(serverUrl, user, password);
        
        configuration.setTimeout(60000);
        configuration.setMarshallingFormat(MarshallingFormat.JSON);
        return  KieServicesFactory.newKieServicesClient(configuration);
    }
    
    private Long startProcess(String version, String processName) {
        String alias = ARTIFACT_ID+"-"+version;
        Long processInstanceId = processClient.startProcess(alias, processName, singletonMap("id", "id1"));
        assertNotNull(processInstanceId);
        return processInstanceId;
    }
     
    private void abortProcess(String version, Long processInstanceId) {
        QueryServicesClient queryClient = ksClient.getServicesClient(QueryServicesClient.class);
        
        ProcessInstance processInstance = queryClient.findProcessInstanceById(processInstanceId);
        assertNotNull(processInstance);
        assertEquals(1, processInstance.getState().intValue());
        String alias = ARTIFACT_ID+"-"+version;
        processClient.abortProcessInstance(alias, processInstanceId);
    }
    

}


