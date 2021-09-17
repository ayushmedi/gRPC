package org.example.grpc.service;

import com.google.api.SystemParameterOrBuilder;
import io.grpc.stub.StreamObserver;
import org.example.grpc.User;
import org.example.grpc.userGrpc.*;

public class UserService extends userImplBase {
    @Override
    public void login(User.LoginRequest request, StreamObserver<User.APIResponse> responseObserver) {
        System.out.println("Inside Login");
        String user = request.getUsername();
        String pass = request.getPassword();
        User.APIResponse.Builder response = User.APIResponse.newBuilder();
        if("admin".equals(user) && "admin".equals(pass)) {
            //success
            response.setResponseCode(200).setResponsemessage("Login Successful!");
        }
        else {
            //failure
            response.setResponseCode(-1).setResponsemessage("Login Failed :( ");
        }
        responseObserver.onNext(response.build());
        //Close the call
        responseObserver.onCompleted();

        System.out.println("Exiting Login");
    }

    @Override
    public void logout(User.Empty request, StreamObserver<User.APIResponse> responseObserver) {
        System.out.println("Inside Logout");
        System.out.println("Exiting Logout");
    }
}
