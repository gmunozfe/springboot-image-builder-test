package org.kie.server.springboot.samples;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.DigestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.LazyFuture;

public class SpringbootKieContainer extends GenericContainer<SpringbootKieContainer>{

    private static final int KIE_PORT = 8090;
    private static final Logger logger = LoggerFactory.getLogger(SpringbootKieContainer.class);
    
    static {
    	File cwd = new File("./src/test/resources/kjars/quartz-cluster-sample");
    	
    	Properties properties = new Properties();
        // Avoid recursion
        properties.put("skipTests", "true");

        InvocationRequest request = new DefaultInvocationRequest()
                .setPomFile(new File(cwd, "pom.xml"))
                .setGoals(Arrays.asList("clean","install"))
                .setInputStream(new ByteArrayInputStream(new byte[0]))
                .setProperties(properties);

        InvocationResult invocationResult = null;
        try {
            invocationResult = new DefaultInvoker().execute(request);
        } catch (MavenInvocationException e) {
            throw new RuntimeException(e);
        }

        if (invocationResult!=null && invocationResult.getExitCode() != 0) {
            throw new RuntimeException(invocationResult.getExecutionException());
        }
    }
    
    private static final Future<String> IMAGE_FUTURE = new LazyFuture<String>() {
        @Override
        protected String resolve() {
            File cwd = new File(".");
            
            // Make it unique per folder (for caching)
            String imageName = String.format(
                    "local/kie-server-springboot:%s",
                    System.currentTimeMillis()
            );
            
            logger.info("imageName: "+imageName);
            
            Properties properties = new Properties();
            properties.put("spring-boot.build-image.name", imageName);
            // Avoid recursion
            properties.put("skipTests", "true");
            
            InvocationRequest request = new DefaultInvocationRequest()
                    .setPomFile(new File(cwd, "pom.xml"))
                    .setGoals(Arrays.asList("spring-boot:build-image"))
                    .setInputStream(new ByteArrayInputStream(new byte[0]))
                    .setProperties(properties);

            InvocationResult invocationResult = null;
            try {
                invocationResult = new DefaultInvoker().execute(request);
            } catch (MavenInvocationException e) {
                throw new RuntimeException(e);
            }

            if (invocationResult!=null && invocationResult.getExitCode() != 0) {
                throw new RuntimeException(invocationResult.getExecutionException());
            }
            return imageName;
        }
    };
    
    public SpringbootKieContainer() {
        super(IMAGE_FUTURE);
        withExposedPorts(KIE_PORT);
        withLogConsumer(new ToStringConsumer() {
                    @Override
                    public void accept(OutputFrame outputFrame) {
                        String message = outputFrame.getUtf8String().replaceAll("\\r?\\n?$", "");
                        logger.info(message);
                    }
                });
        
        
        withNetwork(Network.SHARED);
        withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/rhpamdatabase?user=rhpamuser&password=rhpampassword");
        withEnv("quartz.datasource.url", "jdbc:postgresql://db:5432/rhpamdatabase?user=rhpamuser&password=rhpampassword");
        waitingFor(Wait.forLogMessage(".*Started KieServerApplication in.*", 1).withStartupTimeout(Duration.ofMinutes(5L)));
    }

}