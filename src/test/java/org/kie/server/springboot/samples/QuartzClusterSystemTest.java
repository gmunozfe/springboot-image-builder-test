/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static java.util.Collections.singletonMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

//import org.jbpm.kie.services.impl.KModuleDeploymentUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kie.api.task.model.Status;

import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.api.model.instance.ProcessInstance;
import org.kie.server.api.model.instance.TaskSummary;
import org.kie.server.client.KieServicesClient;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.QueryServicesClient;
import org.kie.server.client.UserTaskServicesClient;
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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


@Testcontainers(disabledWithoutDocker=true)
public class QuartzClusterSystemTest{

    static final String ARTIFACT_ID = "quartz-cluster-sample";
    static final String GROUP_ID = "org.kie.server.testing";
    static final String VERSION = "1.0.0";
    
    public static final String DEFAULT_USER = "kieserver";
    public static final String DEFAULT_PASSWORD = "kieserver1!";
    
    private static final Logger logger = LoggerFactory.getLogger(QuartzClusterSystemTest.class);
    
    public static String containerId = GROUP_ID+":"+ARTIFACT_ID+":"+VERSION;
    
    private String processId = "evaluation";
    private String restrictedVarProcessId = "HumanTaskWithRestrictedVar";
    
    private static KieServicesClient ksClient1;
    private static KieServicesClient ksClient2;
    
    private static ProcessServicesClient processClient1;
    private static UserTaskServicesClient taskClient2;
    
    private static HikariDataSource ds;
    
    public static final String SELECT_COUNT_FROM_QRTZ_TRIGGERS = "select count(*) from qrtz_triggers";
    public static final String SELECT_SCHED_NAME_FROM_QRTZ_TRIGGERS = "select sched_name from qrtz_triggers";
    
    @Container
    static PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>(System.getProperty("org.kie.samples.image.postgresql","postgres:latest"))
            .withDatabaseName("rhpamdatabase")
            .withUsername("rhpamuser")
            .withPassword("rhpampassword").withNetwork(Network.SHARED)
            .withFileSystemBind("./src/test/resources/etc/postgresql", "/docker-entrypoint-initdb.d",
                    BindMode.READ_ONLY)
            .withNetworkAliases("db");
    
    @Container
    static SpringbootKieContainer springboot1 = new SpringbootKieContainer();
        
    @Container
    static SpringbootKieContainer springboot2 = new SpringbootKieContainer();
    
    
    @BeforeAll
    static void startTestContainers() {
        assumeTrue(isDockerAvailable());
    }

    @AfterAll
    static void tearDown() throws Exception {
        springboot1.stop();
        springboot2.stop();
        DockerClient docker = DockerClientFactory.instance().client();
        ListImagesCmd listImagesCmd = docker.listImagesCmd();
        listImagesCmd.getFilters().put("reference", Arrays.asList("local/kie-server-springboot"));
        listImagesCmd.exec().stream()
         .filter(c -> c.getId() != null)
         .forEach(c -> docker.removeImageCmd(c.getId()).withForce(true).exec());
    }

    @BeforeEach
    void setup() {
        
        logger.info("KIE_SPRINGBOOT1 started at %s:%s", springboot1.getContainerIpAddress(), springboot1.getFirstMappedPort());
        logger.info("KIE_SPRINGBOOT2 started at %s:%s", springboot2.getContainerIpAddress(), springboot2.getFirstMappedPort());
        
        ksClient1 = authenticate(springboot1.getContainerIpAddress()+":"+springboot1.getFirstMappedPort(), DEFAULT_USER, DEFAULT_PASSWORD);
        processClient1 = ksClient1.getServicesClient(ProcessServicesClient.class);
        //createContainer(ksClient1);
        
        ksClient2 = authenticate(springboot1.getContainerIpAddress()+":"+springboot1.getFirstMappedPort(), DEFAULT_USER, DEFAULT_PASSWORD);
        taskClient2 = ksClient2.getServicesClient(UserTaskServicesClient.class);
        //createContainer(ksClient2);
        
        ds = getDataSource();
        
    }
    
    @AfterEach
    void cleanup() {
        /*if (deploymentService!=null) {
            deploymentService.undeploy(unit);
        }
        if (kieServicesClient != null) {
            kieServicesClient.disposeContainer(containerId);
        }*/
    }
    
    @Test
    @DisplayName("user starts process in one node but complete task in another before refresh-time")
    @Timeout(120)
    void completeTaskBeforeRefresh() throws Exception {
        Long processInstanceId = startProcessInNode1("process2HumanTasks");
        
        Long taskId = startTaskInNode2(processInstanceId);
        
        assertEquals("there should be just one timer at the table",
                1, performQuery(SELECT_COUNT_FROM_QRTZ_TRIGGERS).getInt(1));
        
        assertEquals("timer should belong to kieClusteredScheduler",
                "kieClusteredScheduler", performQuery(SELECT_SCHED_NAME_FROM_QRTZ_TRIGGERS).getString(1));
        
        taskClient2.completeTask(containerId, taskId, DEFAULT_USER, null);
        logger.info("Completed task {} on kieserver2", taskId);
        
        Thread.sleep(1000);
        
        assertEquals("there shouldn't be any timer at the table",
                0, performQuery(SELECT_COUNT_FROM_QRTZ_TRIGGERS).getInt(1));
        
        List<TaskSummary> taskList = findReadyTasks(processInstanceId);

        assertEquals(1, taskList.size());
        
        abortProcess(ksClient2, processInstanceId);
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
    
    private static void createContainer(KieServicesClient client) {
        ReleaseId releaseId = new ReleaseId(GROUP_ID, ARTIFACT_ID, VERSION);
        KieContainerResource resource = new KieContainerResource(containerId, releaseId);
        client.createContainer(containerId, resource);
    }
    
    private Long startProcessInNode1(String processName) {
        Long processInstanceId = processClient1.startProcess(containerId, processName, singletonMap("id", "id1"));
        assertNotNull(processInstanceId);
        return processInstanceId;
    }
    
    private Long startTaskInNode2(Long processInstanceId) {
        List<TaskSummary> taskList = findReadyTasks(processInstanceId);

        Long taskId = taskList.get(0).getId();
        logger.info("Starting task {} on kieserver2", taskId);
        taskClient2.startTask(containerId, taskId, DEFAULT_USER);
        return taskId;
    }
    
    private void abortProcess(KieServicesClient kieServicesClient, Long processInstanceId) {
        QueryServicesClient queryClient = kieServicesClient.getServicesClient(QueryServicesClient.class);
        
        ProcessInstance processInstance = queryClient.findProcessInstanceById(processInstanceId);
        assertNotNull(processInstance);
        assertEquals(1, processInstance.getState().intValue());
        processClient1.abortProcessInstance(containerId, processInstanceId);
    }
    
    private List<TaskSummary> findReadyTasks(Long processInstanceId) {
        List<String> status = Arrays.asList(Status.Ready.toString());
        List<TaskSummary> taskList = taskClient2.findTasksByStatusByProcessInstanceId(processInstanceId, status, 0, 10);
        return taskList;
    }
    
    private static HikariDataSource getDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgresql.getJdbcUrl());
        hikariConfig.setUsername(postgresql.getUsername());
        hikariConfig.setPassword(postgresql.getPassword());
        hikariConfig.setDriverClassName(postgresql.getDriverClassName());
        
        return new HikariDataSource(hikariConfig);
    }
    
    protected ResultSet performQuery(String sql) throws SQLException {
        try (Connection conn = ds.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
           ResultSet rs = st.executeQuery();
           rs.next();
           return rs;
        }
    }
}


