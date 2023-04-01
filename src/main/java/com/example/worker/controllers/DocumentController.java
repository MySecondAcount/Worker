package com.example.worker.controllers;

import com.example.worker.DAO.DAO;
import com.example.worker.indexing.PropertyIndexManager;
import com.example.worker.model.ApiResponse;
import com.example.worker.services.affinity.AffinityManager;
import com.example.worker.services.authentication.AuthenticationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import org.json.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import static com.example.worker.services.FileServices.*;

@RestController
@RequestMapping("/api")
public class DocumentController {
    private DAO dao = new DAO();
    private PropertyIndexManager propertyIndexManager = PropertyIndexManager.getInstance();
    private AffinityManager affinityManager = AffinityManager.getInstance();
    private RestTemplate restTemplate = new RestTemplate();
    private AuthenticationService authenticationService = new AuthenticationService();

    public boolean validJSONObject(String json, File schemaFile) {
        try {
            String withoutId = removeIdFromDoc(json);
            // Read the schema file
            String schema = readFileAsString(schemaFile);

            // Parse the schema and create a validator
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance();
            JsonSchema validator = factory.getSchema(schema);

            // Parse the JSON string
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(withoutId);

            // Validate the JSON against the schema
            Set<ValidationMessage> errors = validator.validate(jsonNode);

            // If there are no errors, then the JSON is valid
            return errors.isEmpty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @PostMapping("/insertOne/{db_name}/{collection_name}")
    public ApiResponse insertOne(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @RequestBody String json,
            @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagatedRequest,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token
    ) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");
        File schemaFile = new File(DATABASES_DIRECTORY + dbName + SCHEMAS_DIRECTORY + collectionName + ".json");

        if (propagatedRequest) { // Just take the new copy of the data. (no need to propagate it again)
            dao.addDocument(collectionFile, json);
            propertyIndexManager.indexingNewObject(dbName, collectionName, new JSONObject(json));
            return new ApiResponse("Document inserted successfully.", 200);
        }
        if (!isDatabaseExists(dbDirectory)) {
            return new ApiResponse("Database does not exist.", 400);
        }
        if (!isCollectionExists(collectionFile)) {
            return new ApiResponse("Collection does not exist.", 400);
        }
        if (!validJSONObject(json, schemaFile)) {
            return new ApiResponse("Invalid JSON object.", 400);
        }
        // the current worker is the affinity worker
        if (affinityManager.isCurrentWorkerAffinity()) {

            json = addIdToDocument(json);
            // propagating the new document to all workers
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/insertOne/" + dbName + "/" + collectionName;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>(json, headers);
                restTemplate.postForObject(url, requestEntity, ApiResponse.class);
            }
            // Marking the current worker as affinity node for the new document
            JSONObject jsonObject = new JSONObject(json);
            String id = jsonObject.getString("_id");

            // propagating the affinity data to all workers
            affinityManager.propagateAddingAffinity(id, affinityManager.getCurrentWorkerName());

            // Passing the affinity worker to the next worker
            passTheAffinityToNextWorker();
        } else {
            // The current worker is not the affinity worker
            // search for the affinity worker.
            for (String worker : affinityManager.getWorkers()) {
                {
                    String url = "http://" + worker + ":8081/api/isAffinity";
                    HttpEntity<String> requestEntity = new HttpEntity<>("", null);
                    ResponseEntity<Boolean> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Boolean.class);
                    if (responseEntity.getBody()) {
                        String affinityUrl = "http://" + worker + ":8081/api/insertOne/" + dbName + "/" + collectionName;
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("X-Username", username);
                        headers.set("X-Token", token);
                        HttpEntity<String> affinityRequestEntity = new HttpEntity<>(json, headers);
                        restTemplate.postForObject(affinityUrl, affinityRequestEntity, ApiResponse.class);
                        break;
                    }
                }
            }
        }
        return new ApiResponse("Document inserted successfully.", 200);
    }

    private void passTheAffinityToNextWorker() {
        affinityManager.unsetCurrentWorkerAffinity();

        // first getting the name of the current worker
        String currentWorkerName = affinityManager.getCurrentWorkerName(), nextWorkerName;
        if (currentWorkerName.equals("worker1")) {
            nextWorkerName = "worker2";
        } else if (currentWorkerName.equals("worker2")) {
            nextWorkerName = "worker3";
        } else
            nextWorkerName = "worker1";

        String url = "http://" + nextWorkerName + ":8081/api/setAffinity";
        HttpEntity<String> requestEntity = new HttpEntity<>("", null);
        restTemplate.exchange(url, HttpMethod.GET, requestEntity, Void.class);
    }

    @GetMapping("/getDoc/{db_name}/{collection_name}/{doc_id}")
    @ResponseBody
    public ApiResponse getDoc(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @PathVariable("doc_id") String docId,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");

        if (!isDatabaseExists(dbDirectory)) {
            return new ApiResponse("Database does not exist.", 400);
        }
        if (!isCollectionExists(collectionFile)) {
            return new ApiResponse("Collection does not exist.", 400);
        }

        JSONObject obj = dao.getDocument(dbName, collectionName, docId);
        if (obj == null) {
            return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
        } else {
            return new ApiResponse(obj.toString(), 200);
        }
    }

    @GetMapping("/getAllData/{db_name}/{collection_name}")
    @ResponseBody
    public ApiResponse getAll(
            @PathVariable("db_name") String db_name,
            @PathVariable("collection_name") String collection_name,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token
    ) {
        db_name = db_name.toLowerCase();
        collection_name = collection_name.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        ApiResponse response = new ApiResponse("", 200);
        File collectionFile = new File(DATABASES_DIRECTORY + db_name + "/" + collection_name + ".json");
        if (!collectionFile.exists()) {
            response.setMessage("Collection does not exist.");
            response.setStatusCode(400);
        } else {
            try {
                String data = dao.getAllDocs(collectionFile);
                response.setMessage(data);
                response.setStatusCode(200);
            } catch (Exception e) {
                response.setMessage("Error getting data.");
                response.setStatusCode(500);
            }
        }
        return response;
    }

    @DeleteMapping("/deleteDoc/{db_name}/{collection_name}/{doc_id}")
    @ResponseBody
    public ApiResponse deleteDoc(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @PathVariable("doc_id") String docId,
            @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagatedRequest,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");

        if (!isDatabaseExists(dbDirectory)) {
            return new ApiResponse("Database does not exist.", 400);
        }
        if (!isCollectionExists(collectionFile)) {
            return new ApiResponse("Collection does not exist.", 400);
        }
        // Read the contents of the collection file
        String currentContent = readFileAsString(collectionFile);

        if (propagatedRequest) {
            if (dao.deleteDoc(currentContent, collectionFile, docId)) {
                propertyIndexManager.clearDocumentIndexing(dbName, collectionName, docId);
                affinityManager.removeAffinity(docId);
                return new ApiResponse("Document deleted successfully.", 200);
            } else {
                return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
            }
        }

        // getting the owner affinity port for the document
        String affinityName = affinityManager.getAffinityName(docId);
        if (affinityName == null) {
            return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
        }

        // the document is owned by the current worker
        if (affinityName.equals(affinityManager.getCurrentWorkerName())) {
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/deleteDoc/" + dbName + "/" + collectionName + "/" + docId;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
                restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
            }
        } else {
            String url = "http://" + affinityName + ":8081/api/deleteDoc/" + dbName + "/" + collectionName + "/" + docId;
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Username", username);
            headers.set("X-Token", token);
            HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
            restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
        }
        return new ApiResponse("Document deleted successfully.", 200);
    }

    private String getDataType(File Schema, String property) {
        JSONObject schema = new JSONObject(readFileAsString(Schema));
        String dataType = schema.getJSONObject("properties").getJSONObject(property).getString("type");
        return dataType;
    }

    @PostMapping("/updateDoc/{db_name}/{collection_name}/{doc_id}/{property_name}/{new_value}")
    @ResponseBody
    public ApiResponse updateDoc(
            @PathVariable("db_name") String dbName,
            @PathVariable("collection_name") String collectionName,
            @PathVariable("doc_id") String docId,
            @PathVariable("property_name") String propertyName,
            @PathVariable("new_value") Object newValue,
            @RequestHeader(value = "X-Propagate-Request", defaultValue = "false") boolean propagatedRequest,
            @RequestHeader(value = "X-Username") String username,
            @RequestHeader(value = "X-Token") String token,
            @RequestHeader(value = "X-Old-Value", required = false) String oldValue
    ) {
        dbName = dbName.toLowerCase();
        collectionName = collectionName.toLowerCase();
        if (!authenticationService.isAuthenticatedUser(username, token)) {
            return new ApiResponse("User is not authorized.", 401);
        }

        File dbDirectory = new File(DATABASES_DIRECTORY + dbName);
        File collectionFile = new File(DATABASES_DIRECTORY + dbName + "/" + collectionName + ".json");

        if (!isDatabaseExists(dbDirectory)) {
            return new ApiResponse("Database does not exist.", 400);
        }

        if (!isCollectionExists(collectionFile)) {
            return new ApiResponse("Collection does not exist.", 400);
        }

        JSONObject currentObject = dao.getDocument(dbName, collectionName, docId);
        if (currentObject == null) {
            return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
        }

        // Getting the data type from the schema.
        File schemaFile = new File(DATABASES_DIRECTORY + dbName + SCHEMAS_DIRECTORY + collectionName + ".json");
        String dataType = getDataType(schemaFile, propertyName);

        // Casting the new value to the correct data type.
        switch (dataType) {
            case "string":
                newValue = newValue.toString();
                break;
            case "integer":
                newValue = (Integer.parseInt(newValue.toString()));
                break;
            case "number":
                newValue = (Double.parseDouble(newValue.toString()));
                break;
            case "boolean":
                newValue = (Boolean.parseBoolean(newValue.toString()));
                break;
            default:
                return new ApiResponse("Unsupported data type.", 400);
        }

        // new json object
        JSONObject newObject = new JSONObject(currentObject.toString());
        newObject.put(propertyName, newValue);

        // check if the new json object is valid
        if (!validJSONObject(newObject.toString(), schemaFile)) {
            return new ApiResponse("Invalid JSON object.", 400);
        }

        if (propagatedRequest) {
            if (dao.updateDoc(collectionFile, docId, newObject)) {
                propertyIndexManager.updateDocumentIndexing(dbName, collectionName, newObject);
                return new ApiResponse("Document updated successfully.", 200);
            } else {
                return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
            }
        }

        // getting the owner affinity port for the document
        String affinityName = affinityManager.getAffinityName(docId);
        if (affinityName == null) {
            return new ApiResponse("Document with ID " + docId + " not found in collection " + collectionName + ".", 404);
        }

        // if the owner affinity port is the current port
        if (affinityName.equals(affinityManager.getCurrentWorkerName())) {
            // Check whether the old value matches the value inside the affinity node.
            // (Optimistic locking rules)
            if (oldValue != null) {
                String currentObjectValue = currentObject.get(propertyName).toString();
                if (!oldValue.equals(currentObjectValue)) {
                    return new ApiResponse("your version of this document doesn't match the up-to-date document (optimistic looking rules violation)", 400);
                }
            }
            // Propagate the request to all the nodes.
            for (String worker : affinityManager.getWorkers()) {
                String url = "http://" + worker + ":8081/api/updateDoc/" + dbName + "/" + collectionName + "/" + docId + "/" + propertyName + "/" + newValue;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Propagate-Request", "true");
                headers.set("X-Username", username);
                headers.set("X-Token", token);
                HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
                restTemplate.postForObject(url, requestEntity, String.class);
            }
        } else {
            String url = "http://" + affinityName + ":8081/api/updateDoc/" + dbName + "/" + collectionName + "/" + docId + "/" + propertyName + "/" + newValue;
            // Sending the current version of data to the affinity.
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Old-Value", currentObject.get(propertyName).toString());
            headers.set("X-Username", username);
            headers.set("X-Token", token);
            HttpEntity<String> requestEntity = new HttpEntity<>("", null);
            restTemplate.postForObject(url, requestEntity, String.class);
        }
        return new ApiResponse("Document updated successfully.", 200);
    }
}