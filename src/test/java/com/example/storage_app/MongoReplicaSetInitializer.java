package com.example.storage_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.DependsOn;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MongoDBContainer;

import jakarta.annotation.PostConstruct;

@TestConfiguration
@DependsOn("mongoDbContainer")
public class MongoReplicaSetInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoReplicaSetInitializer.class);
    private static final int MAX_POLL_ATTEMPTS = 30;
    private static final long POLL_INTERVAL_MS = 1000;

    @Autowired
    private MongoDBContainer mongoDBContainer;

    @PostConstruct
    public void initializeReplicaSet() {
        if (mongoDBContainer == null) {
            log.error("MongoDBContainer bean is null. Cannot initialize replica set.");
            return;
        }

        if (!mongoDBContainer.isRunning()) {
            log.warn("MongoDBContainer is not running when MongoReplicaSetInitializer @PostConstruct is called. " +
                     "Replica set initiation might be skipped or fail if not started by Testcontainers lifecycle yet.");
        }

        try {
            log.info("Attempting to initiate replica set 'rs0' for Testcontainers MongoDB: {}", mongoDBContainer.getContainerId());

            ExecResult configResult = mongoDBContainer.execInContainer("mongosh", "--quiet", "--eval", "rs.conf()");
            boolean alreadyInitializedOrPrimaryExists = false;
            if (configResult.getExitCode() == 0 && configResult.getStdout() != null) {
                String confOutput = configResult.getStdout();
                if (confOutput.contains("setName") && confOutput.contains("rs0")) {
                    ExecResult statusResultCheck = mongoDBContainer.execInContainer("mongosh", "--quiet", "--eval", "JSON.stringify(rs.status())");
                    if (statusResultCheck.getExitCode() == 0 && statusResultCheck.getStdout() != null && statusResultCheck.getStdout().contains("\"stateStr\":\"PRIMARY\"")) {
                        log.info("Replica set 'rs0' is already configured and has a PRIMARY.");
                        alreadyInitializedOrPrimaryExists = true;
                    } else {
                        log.info("Replica set 'rs0' configured but no PRIMARY found yet or error in status. Will attempt initiation if appropriate.");
                    }
                } 
            }

            if (alreadyInitializedOrPrimaryExists) {
                log.info("Replica set 'rs0' appears to be already initialized and operational.");
            } else {
                log.info("Proceeding with replica set 'rs0' initiation.");
                ExecResult initiationResult = mongoDBContainer.execInContainer(
                        "mongosh", "--quiet", "--eval",
                        "var cfg = {_id: 'rs0', members: [{_id: 0, host: \"localhost:27017\"}]}; " +
                        "try { printjson(rs.initiate(cfg)); } catch(e) { printjson(e); }"
                );

                if (initiationResult.getExitCode() == 0 && (initiationResult.getStdout().contains("\"ok\":1") || initiationResult.getStdout().contains("already initialized"))) {
                    log.info("Replica set 'rs0' initiation command executed. STDOUT: {}", initiationResult.getStdout().trim());
                    if (initiationResult.getStderr() != null && !initiationResult.getStderr().trim().isEmpty()) {
                        log.warn("Replica set 'rs0' initiation STDERR: {}", initiationResult.getStderr().trim());
                    }
                    
                    boolean primaryElected = false;
                    for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                        log.info("Polling for PRIMARY state (attempt {}/{})", i + 1, MAX_POLL_ATTEMPTS);
                        ExecResult statusResult = mongoDBContainer.execInContainer("mongosh", "--quiet", "--eval", "JSON.stringify(rs.status())");
                        if (statusResult.getExitCode() == 0 && statusResult.getStdout() != null && statusResult.getStdout().contains("\"stateStr\":\"PRIMARY\"")) {
                            log.info("PRIMARY detected in replica set 'rs0'. STDOUT: {}", statusResult.getStdout().trim());
                            primaryElected = true;
                            break;
                        }
                        Thread.sleep(POLL_INTERVAL_MS);
                    }

                    if (!primaryElected) {
                        log.error("PRIMARY member not elected in replica set 'rs0' after {} attempts.", MAX_POLL_ATTEMPTS);
                        throw new RuntimeException("PRIMARY member not elected in replica set 'rs0' after polling.");
                    }
                    log.info("Replica set 'rs0' initialized and PRIMARY is stable.");
                } else {
                    log.error("Failed to execute/confirm replica set 'rs0' initiation. Exit code: {}. STDOUT: '{}'. STDERR: '{}'",
                            initiationResult.getExitCode(), initiationResult.getStdout().trim(), initiationResult.getStderr().trim());
                    throw new RuntimeException("Failed to initialize MongoDB replica set for tests. STDOUT: " + initiationResult.getStdout() + " STDERR: " + initiationResult.getStderr());
                }
            }
        } catch (InterruptedException e) {
            log.error("Thread interrupted during replica set initialization/polling.", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during replica set initialization/polling.", e);
        } catch (Exception e) {
            log.error("Error during replica set 'rs0' initialization/polling for Testcontainers MongoDB: {}", e.getMessage(), e);
            throw new RuntimeException("Error during MongoDB replica set initialization/polling for tests.", e);
        }
    }
} 