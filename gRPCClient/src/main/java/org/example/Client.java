package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.grpc.*;
import org.example.grpc.userGrpc.*;

import java.sql.SQLOutput;

public class Client {

    public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost",30000).usePlaintext().build();

        //stubs
        userBlockingStub userStub = userGrpc.newBlockingStub(channel);
        User.LoginRequest loginRequest = User.LoginRequest.newBuilder().setPassword("admin").setUsername("admin").build();
        User.APIResponse apiResponse = userStub.login(loginRequest);
        System.out.println("API Response code: " + apiResponse.getResponseCode());

    }
}
