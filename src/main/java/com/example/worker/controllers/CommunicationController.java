package com.example.worker.controllers;

import com.example.worker.model.ApiResponse;
import com.example.worker.services.affinity.AffinityManager;
import com.example.worker.services.authentication.AuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommunicationController {
    private AffinityManager affinityManager = AffinityManager.getInstance();
    private AuthenticationService authenticationService;

    @Autowired
    public CommunicationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/addAffinityData/{id}/{workerName}")
    public ApiResponse addAffinityData(@PathVariable("id") String id, @PathVariable("workerName") String workerName) {
        affinityManager.addAffinity(id, workerName);
        return new ApiResponse("Affinity data added successfully!", 200);
    }

    @GetMapping("/setAffinity")
    public ApiResponse setAffinity() {
        affinityManager.setCurrentWorkerAffinity();
        return new ApiResponse("Affinity set successfully!", 200);
    }

    @GetMapping("/addAuthenticatedUser/{username}/{token}")
    public ApiResponse addAuthenticatedUser(@PathVariable("username") String username, @PathVariable("token") String token,
                                            @RequestHeader(value = "X-Username") String adminUserName,
                                            @RequestHeader(value = "X-Token") String adminToken) {
        if (!authenticationService.isAdmin(adminUserName, adminToken)) {
            return new ApiResponse("Non registered admin!", 500);
        }

        authenticationService.addUser(username, token);
        return new ApiResponse("User added successfully!", 200);
    }

    @GetMapping("addAdmin/{newUsername}/{newAdminToken}")
    public ApiResponse addAdmin(@PathVariable("newUsername") String newUsername, @PathVariable("newAdminToken") String newAdminToken
            , @RequestHeader(value = "X-Username") String adminUserName
            , @RequestHeader(value = "X-Token") String adminToken) {

        if (!authenticationService.isAdmin(adminUserName, adminToken)) {
            return new ApiResponse("Non registered admin hasn't the ability to add new admins!", 500);
        }

        authenticationService.addNewAdmin(newUsername, newAdminToken);
        return new ApiResponse("Admin added successfully!", 200);
    }

    @DeleteMapping("/removeAuthenticatedUser/{username}/{token}")
    public ApiResponse removeAuthenticatedUser(
            @PathVariable("username") String username, @PathVariable("token") String token
            , @RequestHeader(value = "X-Username") String adminUserName
            , @RequestHeader(value = "X-Token") String adminToken) {

        if (!authenticationService.isAdmin(adminUserName, adminToken)) {
            return new ApiResponse("Non registered admin!", 500);
        }

        authenticationService.removeUser(username, token);
        return new ApiResponse("User removed successfully!", 200);
    }

    @GetMapping("/unsetAffinity")
    public ApiResponse unsetAffinity() {
        affinityManager.unsetCurrentWorkerAffinity();
        return new ApiResponse("Affinity unset successfully!", 200);
    }

    @GetMapping("/isAffinity")
    public boolean isAffinity() {
        return affinityManager.isCurrentWorkerAffinity();
    }


    @GetMapping("/setCurrentWorkerName/{name}")
    public ApiResponse setCurrentWorkerName(@PathVariable("name") String name) {
        affinityManager.setCurrentWorkerName(name);
        return new ApiResponse("Current worker name set successfully!", 200);
    }

    @GetMapping("/getCurrentWorkerName")
    public String getCurrentWorkerName() {
        return affinityManager.getCurrentWorkerName();
    }
}
