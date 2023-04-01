package com.example.worker.controllers;

import com.example.worker.DAO.DAO;
import com.example.worker.indexing.PropertyIndexManager;
import com.example.worker.model.ApiResponse;
import com.example.worker.model.Schema;
import com.example.worker.services.affinity.AffinityManager;
import com.example.worker.services.authentication.AuthenticationService;
import org.json.JSONArray;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;

import static com.example.worker.services.FileServices.*;

@RestController
@RequestMapping("/api")
public class CollectionController {
    private final RestTemplate restTemplate = new RestTemplate();
    private final Object lock = new Object(); // lock object for synchronization
    private DAO dao = new DAO();
    private PropertyIndexManager propertyIndexManager = PropertyIndexManager.getInstance();
    private AuthenticationService authenticationService = new AuthenticationService();
    private AffinityManager affinityManager = AffinityManager.getInstance();

    @PostMapping("createCol/{db_name}/{collection_name}")
    @ResponseBody
    public ApiResponse addCollection(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @RequestBody Schema schema,
            @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagateRequest,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token
    ) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();

        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }
        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");
        ApiResponse response = new ApiResponse("", 200);

        if (propagateRequest) {
            if (collectionFile.exists()) {
                response.setMessage("Collection already exists.");
                response.setStatusCode(400);
            } else {
                File schemaFile = new File(DATABASES_DIRECTORY + dbName + SCHEMAS_DIRECTORY + collectionName + ".json");
                dao.createCollection(collectionFile, schemaFile, schema);
                response.setMessage("Collection created successfully.");
                response.setStatusCode(200);
            }
        } else {


            // Propagate the request to the other nodes
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/createCol/" + dbName + "/" + collectionName;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<Schema> requestEntity = new HttpEntity<>(schema, headers);
                restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
                response.setMessage("Collection created successfully.");
                response.setStatusCode(200);
            }
        }

        return response;
    }

    // deleting the collection on the current link : /api/deleteCol/db/{db_name}/collection/{collection_name}
    @DeleteMapping("/deleteCol/{db_name}/{collection_name}")
    @ResponseBody
    public ApiResponse deleteCollection(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagateRequest,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token
    ) {

        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");
        ApiResponse response = new ApiResponse("", 200);

        if (!collectionFile.exists()) {
            response.setMessage("Collection does not exist.");
            response.setStatusCode(400);
            return response;
        }

        if (propagateRequest) {
            // clearing the cache and the indexing
            try {
                dao.clearCollectionCaching(collectionFile);
                propertyIndexManager.clearCollectionIndexing(dbName, collectionName);
                File schemaFile = new File(DATABASES_DIRECTORY + dbName + SCHEMAS_DIRECTORY + collectionName + ".json");

                synchronized (lock) {
                    collectionFile.delete();
                    schemaFile.delete();
                }

                response.setMessage("Collection deleted successfully.");
                response.setStatusCode(200);

            } catch (Exception e) {
                response.setMessage("Error deleting collection.");
                response.setStatusCode(500);
            }
        } else {
            // Propagate the request to the other nodes
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":/api/deleteCol/" + dbName.toLowerCase() + "/" + collectionName.toLowerCase();
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>(headers);
                restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
            }
        }
        return response;
    }


    @GetMapping("/filter/{db_name}/{collectionName}")
    @ResponseBody
    public ApiResponse filterCollection(
            @PathVariable("db_name") String dbName,
            @PathVariable("collectionName") String collectionName,
            @RequestParam("attributeName") String attributeName,
            @RequestParam("attributeValue") String attributeValue,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token
    ) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        // to check if the db exists
        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        if (!dbDirectory.exists() && !dbDirectory.isDirectory()) {
            return new ApiResponse("Database does not exist.", 400);
        }

        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");
        ApiResponse response = new ApiResponse("", 200);

        if (!collectionFile.exists()) {
            response.setMessage("Collection does not exist.");
            response.setStatusCode(400);
            return response;
        }

        // First, check if the index exists
        JSONArray collections = propertyIndexManager.getMatchingDocs(dbName, collectionName, attributeName, attributeValue);
        if (collections != null) {
            response.setMessage(collections.toString());
            response.setStatusCode(200);
            return response;
        }

        // else, read it from the file system
        response.setMessage(dao.getFilteredData(collectionFile, attributeName, attributeValue).toString());
        response.setStatusCode(200);

        return response;
    }

    @GetMapping("getCollections/{db_name}")
    public ApiResponse getCollections(@PathVariable("db_name") String dbName,
                                      @RequestHeader(value = "X-Username") String username,
                                      @RequestHeader(value = "X-Token") String token) {

        dbName = dbName.toLowerCase();

        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        if (!isDatabaseExists(dbDirectory)) {
            return new ApiResponse("Database does not exist.", 400);
        }
        return new ApiResponse(dao.allCollections(dbDirectory), 200);
    }
}