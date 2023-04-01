package com.example.worker.controllers;

import com.example.worker.DAO.DAO;
import com.example.worker.indexing.PropertyIndexManager;
import com.example.worker.model.ApiResponse;
import com.example.worker.services.affinity.AffinityManager;
import com.example.worker.services.authentication.AuthenticationService;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;

import static com.example.worker.services.FileServices.DATABASES_DIRECTORY;
import static com.example.worker.services.FileServices.SCHEMAS_DIRECTORY;

@RestController
@RequestMapping("/api")
public class DatabaseController {
    private DAO dao = new DAO();
    private PropertyIndexManager propertyIndexManager = PropertyIndexManager.getInstance();
    private AuthenticationService authenticationService = new AuthenticationService();
    private RestTemplate restTemplate = new RestTemplate();
    private AffinityManager affinityManager = AffinityManager.getInstance();
    private Logger logger = LoggerFactory.getLogger(CollectionController.class);

    @GetMapping("/createDB/{name}")
    public ApiResponse createDatabase(@PathVariable("name") String name,
                                      @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagatedRequest,
                                      @RequestHeader(value = "X-Username") String username,
                                      @RequestHeader(value = "X-Token") String token) {

        logger.info("Creating database " + name + (propagatedRequest ? " (propagated)" : ""));
        name = name.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            logger.warn("User is not authorized.");
            return new ApiResponse("User is not authorized.", 401);
        }

        // Check if the database directory exists
        File dbDirectory = new File(DATABASES_DIRECTORY + name);
        if (dbDirectory.exists() && dbDirectory.isDirectory()) {
            logger.warn("Database already exists.");
            return new ApiResponse("Database already exists.", 400);
        }

        // Propagate the request to the other nodes
        if (!propagatedRequest) {
            logger.info("Propagating request to other nodes.");
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/createDB/" + name;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
                restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            }
        } else {
            // Create the database directory
            if (!dbDirectory.mkdirs()) {
                logger.error("Error creating database.");
                return new ApiResponse("Error creating database.", 500);
            } else {
                // creating the schemas directory
                File schemasDirectory = new File(DATABASES_DIRECTORY + name + SCHEMAS_DIRECTORY);
                schemasDirectory.mkdirs();
            }
        }

        logger.info("Database created successfully.");
        return new ApiResponse("Database created successfully.", 200);
    }

    @DeleteMapping("deleteDB/{name}")
    public ApiResponse deleteDatabase(@PathVariable("name") String name
            , @RequestHeader(value = "X-PropagateRequest", defaultValue = "false") boolean propagatedRequest,
                                      @RequestHeader(value = "X-Username") String username,
                                      @RequestHeader(value = "X-Token") String token) {

        logger.info("Deleting database " + name + (propagatedRequest ? " (propagated)" : ""));
        name = name.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }


        File dbDirectory = new File(DATABASES_DIRECTORY + name);
        if (!dbDirectory.exists() && !dbDirectory.isDirectory()) {
            logger.warn("Database does not exist.");
            return new ApiResponse("Database does not exist.", 400);
        }

        if (propagatedRequest) {
            try {
                propertyIndexManager.clearDBIndexing(name);
                dao.clearDBCache(dbDirectory);
                FileUtils.deleteDirectory(dbDirectory);
            } catch (Exception e) {
                return new ApiResponse("Error deleting database.", 500);
            }
        } else {
            logger.info("Propagating request to other nodes.");
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/deleteDB/" + name;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
                restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
            }
        }

        logger.info("Database deleted successfully.");
        return new ApiResponse("Database deleted successfully.", 200);
    }

    @GetMapping("/listDB")
    public ApiResponse listDatabases(@RequestHeader(value = "X-Username") String username,
                                     @RequestHeader(value = "X-Token") String token) {
        logger.info("Requesting list of databases.");

        if (!authenticationService.isAuthenticatedUser(username, token)) {
            logger.warn("User is not authorized.");
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY);
        return new ApiResponse(dao.listDbs(dbDirectory), 200);
    }
}
