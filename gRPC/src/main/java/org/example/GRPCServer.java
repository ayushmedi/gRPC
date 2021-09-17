package org.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.example.grpc.service.UserService;

import java.io.IOException;

public class GRPCServer {

    public static void main(String[] args) {
        Server server = ServerBuilder.forPort(30000).addService(new UserService()).build();

        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("gRPC server started at port " + 30000);

        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
